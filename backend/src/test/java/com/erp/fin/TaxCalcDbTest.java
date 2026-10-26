package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.TaxCalcLedgerDao;
import com.erp.entity.fin.TaxCalcLedger;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.TaxCalcService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 计税引擎 DbTest（add-tax-calculation：spec tax-calculation 5 需求 11 场景）。
 * 共享库隔离：junit 前缀造数 + purge；期间 209902；税码用既有 VAT13/06/09。
 */
@SpringBootTest
class TaxCalcDbTest {

    private static final String PERIOD = "209902";

    @Autowired
    private TaxCalcService taxCalcService;
    @Autowired
    private TaxCalcLedgerDao ledgerDao;
    @Autowired
    private GlVoucherService voucherService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        login("ROLE_FINANCE");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        // 整域清理（同段 0 SOURCE_TYPE 整类删先例）：冒烟残留（user-admin 假税码行）
        // 会污染 taxCodeExpiry 红标与 redCount 断言——不按创建人前缀过滤
        jdbc.update("DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE = 'TAXCALC'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'TAXCALC'");
        jdbc.update("DELETE FROM erp_fin_tax_calc_ledger");
        jdbc.update("DELETE FROM erp_fin_balance WHERE PERIOD = '209902' "
                + "AND ACCOUNT_CODE IN ('222101','222102')");
    }

    private void login(String role) {
        loginAs("junit", role);
    }

    private void loginAs(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, role));
    }

    // ================================================================
    // 2.1 税码匹配（FR-4.14-1-2 / C-4.14-01 / BR-4.14-10）
    // ================================================================

    @Test
    void matchUniqueAutoSelect() {
        Map<String, Object> out = taxCalcService.match("SALES", "DOMESTIC", "GENERAL", "STANDARD",
                LocalDate.of(2026, 10, 23));
        assertEquals(Boolean.FALSE, out.get("multiCandidate"), "VAT13 STANDARD 唯一命中");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("candidates");
        assertEquals(1, hits.size());
        assertEquals("VAT13", hits.get(0).get("taxCode"));
    }

    @Test
    void matchMultiCandidateL4() {
        Map<String, Object> out = taxCalcService.match("PURCHASE", "DOMESTIC", "GENERAL", "LOW",
                LocalDate.of(2026, 10, 23));
        assertEquals(Boolean.TRUE, out.get("multiCandidate"), "VAT06/VAT09 同为 LOW 多命中");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("candidates");
        assertEquals(2, hits.size());
    }

    @Test
    void matchExpiredBlocked422() {
        // 不存在的 SCOPE → 无任何候选 → C-4.14-01 L1 阻断
        ServiceException ex = assertThrows(ServiceException.class,
                () -> taxCalcService.match("SALES", "BOND", "GENERAL", null, LocalDate.of(2026, 10, 23)));
        assertTrue(ex.getMessage().contains("税率主数据缺失或已过期"), ex.getMessage());
    }

    @Test
    void matchBusinessDateBeforeEffective() {
        // 业务发生日 1999 年——早于全部税码生效日（2020-01-01）→ 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> taxCalcService.match("SALES", "DOMESTIC", "GENERAL", "STANDARD", LocalDate.of(1999, 1, 1)));
        assertTrue(ex.getMessage().contains("C-4.14-01"), ex.getMessage());
    }

    // ================================================================
    // 2.2 税额计算（FR-4.14-1-3/4 / BR-4.14-07/08/12）
    // ================================================================

    @Test
    void calcGrossSeparation() {
        Map<String, Object> p = payload();
        p.put("calcType", "GENERAL");
        p.put("grossFlag", "Y");
        p.put("taxRate", "0.13");
        p.put("lines", List.of(Map.of("amount", "11300")));
        Map<String, Object> out = taxCalcService.calc(p);
        assertEquals(0, new BigDecimal("10000").compareTo(
                new BigDecimal(String.valueOf(out.get("taxableBase")))), "价税分离基数");
        assertEquals(0, new BigDecimal("1300").compareTo(new BigDecimal(String.valueOf(out.get("taxAmount")))), "税额");
        assertEquals(Boolean.FALSE, out.get("overTolerance"));
        assertEquals("Y", out.get("deductible"));
    }

    @Test
    void calcMultiRateDiscountAllocation() {
        // BR-4.14-12：两行不含税 10000(VAT13)+5000(VAT06)，折扣 1500 按占比分摊
        // → 行一 9000×13%=1170、行二 4500×6%=270、合计 1440
        Map<String, Object> p = payload();
        p.put("calcType", "GENERAL");
        p.put("grossFlag", "N");
        p.put("discount", "1500");
        p.put("lines", List.of(
                Map.of("amount", "10000", "taxRate", "0.13"),
                Map.of("amount", "5000", "taxRate", "0.06")));
        Map<String, Object> out = taxCalcService.calc(p);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) out.get("lines");
        assertEquals(0, new BigDecimal("1170").compareTo(new BigDecimal(String.valueOf(lines.get(0).get("taxAmount")))));
        assertEquals(0, new BigDecimal("270").compareTo(new BigDecimal(String.valueOf(lines.get(1).get("taxAmount")))));
        assertEquals(0, new BigDecimal("1440").compareTo(new BigDecimal(String.valueOf(out.get("taxAmount")))));
    }

    @Test
    void calcSimpleNotDeductible() {
        Map<String, Object> p = payload();
        p.put("calcType", "SIMPLE");
        p.put("grossFlag", "N");
        p.put("taxRate", "0.03");
        p.put("lines", List.of(Map.of("amount", "10000")));
        Map<String, Object> out = taxCalcService.calc(p);
        assertEquals("N", out.get("deductible"), "BR-4.14-08 简易不得抵扣");
        assertEquals(0, new BigDecimal("300").compareTo(new BigDecimal(String.valueOf(out.get("taxAmount")))));
    }

    // ================================================================
    // 3.1~3.2 台账与入账（FR-4.14-1-4/5，DRAFT 三态）
    // ================================================================

    @Test
    void ledgerRecordedThenApplyThenVoucherDraft() {
        String ledgerId = seedLedger("SALES", "Y", "0.13", "1300", "10000");
        // 仅留痕 → 无凭证（RECORDED）
        assertEquals(TaxCalcLedger.STATUS_RECORDED, ledgerDao.selectById(ledgerId).getStatus());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> taxCalcService.generateVoucher(ledgerId));
        assertTrue(ex.getMessage().contains("入账申请"), ex.getMessage());

        // 补录三字段 → APPLYING
        taxCalcService.submitApply(ledgerId, "冒烟客户A", PERIOD, "手工计税补提销项");
        assertEquals(TaxCalcLedger.STATUS_APPLYING, ledgerDao.selectById(ledgerId).getStatus());

        // 生成凭证 → DRAFT 两行平衡，锚 TAXCALC/ledgerId
        Map<String, Object> v = taxCalcService.generateVoucher(ledgerId);
        assertEquals("DRAFT", v.get("status"), "DRAFT 三态，过账前不动总账");
        TaxCalcLedger row = ledgerDao.selectById(ledgerId);
        assertEquals(TaxCalcLedger.STATUS_VOUCHED, row.getStatus());
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT ACCOUNT_CODE, DIRECTION, AMOUNT FROM erp_fin_voucher_line "
                        + "WHERE VOUCHER_ID = ? ORDER BY FIELD(DIRECTION,'DR','CR')", row.getVoucherId());
        assertEquals(2, lines.size());
        assertEquals("1122", lines.get(0).get("ACCOUNT_CODE"), "借 1122 应收账款");
        assertEquals("222102", lines.get(1).get("ACCOUNT_CODE"), "贷 222102 销项税");

        // 重复生成 422
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> taxCalcService.generateVoucher(ledgerId));
        assertTrue(ex2.getMessage().contains("仅入账申请"), ex2.getMessage());
    }

    @Test
    void simpleVoucherToExpenseNotInputTax() {
        String ledgerId = seedLedger("PURCHASE", "N", "0.03", "300", "10000"); // SIMPLE → deductible=N
        taxCalcService.submitApply(ledgerId, "冒烟供应商B", PERIOD, "简易计税零星采购");
        taxCalcService.generateVoucher(ledgerId);
        String vid = ledgerDao.selectById(ledgerId).getVoucherId();
        List<String> accts = jdbc.queryForList(
                "SELECT ACCOUNT_CODE FROM erp_fin_voucher_line WHERE VOUCHER_ID = ? "
                        + "ORDER BY FIELD(DIRECTION,'DR','CR')", String.class, vid);
        assertEquals(List.of("660299", "2202"), accts, "SIMPLE 借费用不进 222101（BR-4.14-08）");
    }

    @Test
    void applyMissingField422() {
        String ledgerId = seedLedger("SALES", "Y", "0.13", "1300", "10000");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> taxCalcService.submitApply(ledgerId, "", PERIOD, "缺对手方"));
        assertTrue(ex.getMessage().contains("三字段"), ex.getMessage());
    }

    // ================================================================
    // 4.1 合规校验报告（核心能力 4）
    // ================================================================

    @Test
    void reportReconGreenAfterPost() {
        // 销项台账行 → 入账 → 审核（换人）→ 过账 → 勾稽应一致（绿）
        String ledgerId = seedLedger("SALES", "Y", "0.13", "1300", "10000");
        taxCalcService.submitApply(ledgerId, "勾稽客户", PERIOD, "勾稽测试");
        Map<String, Object> v = taxCalcService.generateVoucher(ledgerId);
        loginAs("junit-mgr", "ROLE_FIN_MGR");
        voucherService.audit((String) v.get("voucherId"));
        voucherService.post((String) v.get("voucherId"));

        Map<String, Object> report = taxCalcService.complianceReport(PERIOD);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) report.get("items");
        Map<String, Object> recon = items.stream()
                .filter(i -> "glRecon".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals("ok", recon.get("level"), "过账后台账 1300 vs 总账 222102 CR 1300 一致");
        assertEquals(Boolean.FALSE, report.get("allGreen"), "分类编码黄标恒在 → 非全绿");
        assertEquals(0L, report.get("redCount"));
    }

    @Test
    void reportReconDiffRed() {
        // 台账行入账过账后，人为改总账余额行制造差异 → 红标
        String ledgerId = seedLedger("SALES", "Y", "0.13", "1300", "10000");
        taxCalcService.submitApply(ledgerId, "差异客户", PERIOD, "差异测试");
        Map<String, Object> v = taxCalcService.generateVoucher(ledgerId);
        loginAs("junit-mgr", "ROLE_FIN_MGR");
        voucherService.audit((String) v.get("voucherId"));
        voucherService.post((String) v.get("voucherId"));
        jdbc.update("UPDATE erp_fin_balance SET CR_TOTAL = CR_TOTAL + 100 "
                + "WHERE PERIOD = ? AND ACCOUNT_CODE = '222102'", PERIOD);

        Map<String, Object> report = taxCalcService.complianceReport(PERIOD);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) report.get("items");
        Map<String, Object> recon = items.stream()
                .filter(i -> "glRecon".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals("red", recon.get("level"), "差异 100 → 红标");
        assertTrue(report.get("redCount").toString().equals("1"));
    }

    // ---------- helpers ----------

    private Map<String, Object> payload() {
        return new LinkedHashMap<>();
    }

    /** 造台账行：calc 已算好 → ledger 落库 */
    private String seedLedger(String trxType, String grossFlagOrDeductibleMarker,
                              String rate, String tax, String base) {
        boolean simple = "0.03".equals(rate);
        Map<String, Object> p = payload();
        p.put("trxType", trxType);
        p.put("scope", "DOMESTIC");
        p.put("calcType", simple ? "SIMPLE" : "GENERAL");
        p.put("rateKind", simple ? null : "STANDARD");
        p.put("businessDate", "2026-10-23");
        p.put("amount", base);
        p.put("grossFlag", "N");
        p.put("taxCode", simple ? "VAT-SIMPLE-TEST" : "VAT13");
        p.put("taxRate", rate);
        p.put("taxableBase", base);
        p.put("taxAmount", tax);
        p.put("deductible", simple ? "N" : "Y");
        Map<String, Object> out = taxCalcService.ledger(p);
        return (String) out.get("id");
    }
}
