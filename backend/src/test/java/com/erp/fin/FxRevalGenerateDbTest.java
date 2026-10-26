package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FxRevalDao;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FxReval;
import com.erp.service.fin.FxRevalService;
import com.erp.service.fin.GlVoucherService;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.fin.FinVoucherLine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-fx-revaluation task 4.3：生成重估批次（spec fx-revaluation，FR-4.6-4-5/6，BR-4.6-25）。
 * 生成落库 / 同期幂等阻断 / 上期红冲链 / 上期草稿作废 / 失败整体回滚。
 */
@SpringBootTest
class FxRevalGenerateDbTest {

    @Autowired
    private FxRevalService revalService;
    @Autowired
    private FxRevalDao revalDao;
    @Autowired
    private FinApInvoiceDao apDao;
    @Autowired
    private GlVoucherService voucherService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 本期 209910（期末 2099-10-31），上期 209909 */
    private static final String CUR = "209910";
    private static final String PREV = "209909";

    @BeforeEach
    void setUp() {
        purge();
        // 显式登录保证制单人确定（防前序测试遗留 SecurityContext 污染 C-4.6-07 断言）
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                        "junit", null, "ROLE_ADMIN"));
        // 期末中间价：USD 209909=7.18 / 209910=7.20（月区间）
        jdbc.update("INSERT IGNORE INTO erp_mdm_exchange_rate (ID,BASE_CCY,QUOTE_CCY,RATE_TYPE,"
                + "EFFECTIVE_DATE,EXPIRE_DATE,RATE,SOURCE_FILE_NO,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('fx-gen-sep','USD','CNY','MIDDLE','2099-09-01','2099-09-30',7.180000,'央行','junit','0',0),"
                + "('fx-gen-oct','USD','CNY','MIDDLE','2099-10-01','2099-10-31',7.200000,'央行','junit','0',0)");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void purge() {
        // 冒烟段 21 残留（SMKFX 外币项目会被扫描进生成池——共享库隔离）
        jdbc.update("DELETE FROM erp_fin_ap_invoice WHERE INVOICE_NO LIKE 'SMKFX%'");
        jdbc.update("DELETE FROM erp_fin_ar_invoice WHERE AR_NO LIKE 'SMKFX%'");
        jdbc.update("DELETE FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'SMKFX%'");
        jdbc.update("DELETE FROM erp_fin_fx_reval WHERE PERIOD IN ('209909','209910','209911','209912')");
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='REVAL'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='REVAL'");
        jdbc.update("DELETE FROM erp_fin_balance WHERE ACCOUNT_CODE='6604' AND PERIOD IN ('209909','209910','209911','209912')");
        jdbc.update("DELETE FROM erp_fin_period WHERE ID='per-rvt-209910'");
        jdbc.update("DELETE FROM erp_fin_ap_invoice WHERE INVOICE_NO LIKE 'GEN-%'");
        jdbc.update("DELETE FROM erp_mdm_exchange_rate WHERE ID IN ('fx-gen-sep','fx-gen-oct')");
        jdbc.update("DELETE FROM erp_mdm_exchange_rate WHERE ID LIKE 'fx-smkfx-%'");
    }

    /** 造外币 AP 未清（记账汇率可指定） */
    private FinApInvoice seedAp(String no, String rate) {
        FinApInvoice inv = new FinApInvoice();
        inv.setId("GEN-AP-" + no);
        inv.setInvoiceNo("GEN-" + no);
        inv.setSupplierId("SUP-GEN");
        inv.setSupplierName("GEN 供应商");
        inv.setInvoiceDate(LocalDate.of(2099, 8, 1));
        inv.setCurrency("USD");
        inv.setBookingRate(new BigDecimal(rate));
        inv.setTotalAmount(new BigDecimal("10000"));
        inv.setPaidAmount(BigDecimal.ZERO);
        inv.setStatus(FinApInvoice.ST_POSTED);
        apDao.insert(inv);
        return inv;
    }

    private List<Map<String, Object>> voucherLines(String voucherId) {
        return jdbc.queryForList("SELECT ACCOUNT_CODE, DIRECTION, AMOUNT FROM erp_fin_voucher_line "
                + "WHERE VOUCHER_ID=? ORDER BY LINE_NO", voucherId);
    }

    // ---------- 1. 生成落库（台账逐笔 + DRAFT 凭证借贷平衡）----------

    @Test
    void generatePersistsLedgerAndDraftVoucher() {
        seedAp("G1", "7.120000");
        Map<String, Object> out = revalService.generate(CUR);

        assertEquals("REVAL-" + CUR + "-0001", out.get("revalNo"), "批次号");
        assertEquals(1, out.get("itemCount"));
        // 10000 × (7.20 − 7.12) = 800.00 汇兑收益
        assertEquals(0, new BigDecimal("800.00").compareTo((BigDecimal) out.get("totalDiff")));

        // 台账逐笔落库
        List<FxReval> rows = revalDao.selectByPeriod(CUR);
        assertEquals(1, rows.size());
        assertEquals("GEN-G1", rows.get(0).getItemNo());
        assertEquals(0, new BigDecimal("800.00").compareTo(rows.get(0).getDiffAmount()));

        // DRAFT 凭证（不过账，decision④ 待财务主管审核）
        Map<String, Object> vd = voucherService.detail((String) out.get("voucherId"));
        assertEquals("DRAFT", String.valueOf(vd.get("status")), "凭证落 DRAFT 待审核");
        List<Map<String, Object>> lines = voucherLines((String) out.get("voucherId"));
        assertEquals(2, lines.size(), "收益两行：借 AP/贷 6604");
        assertEquals("2202", lines.get(0).get("ACCOUNT_CODE"));
        assertEquals("DR", lines.get(0).get("DIRECTION"));
        assertEquals("6604", lines.get(1).get("ACCOUNT_CODE"));
        assertEquals("CR", lines.get(1).get("DIRECTION"));
        assertEquals(0, new BigDecimal("800.00")
                .compareTo(new BigDecimal(String.valueOf(lines.get(1).get("AMOUNT")))));
    }

    // ---------- 2. 同期重复生成 422 ----------

    @Test
    void samePeriodRegenerateBlocks() {
        seedAp("G2", "7.120000");
        revalService.generate(CUR);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> revalService.generate(CUR));
        assertTrue(ex.getMessage().contains("重复生成"), ex.getMessage());
        // 台账仍一条
        assertEquals(1, revalDao.selectCountByPeriod(CUR));
    }

    // ---------- 3. 上期 POSTED 自动红冲（BR-4.6-25）----------

    @Test
    void priorPostedVoucherAutoReversed() {
        // 上期生成 + 审核 + 过账（模拟上期已完成重估）
        seedAp("G3", "7.100000");
        Map<String, Object> prev = revalService.generate(PREV);
        loginAuditor();
        voucherService.audit((String) prev.get("voucherId"));
        voucherService.post((String) prev.get("voucherId"));

        // 本期生成 → 上期自动红冲
        Map<String, Object> out = revalService.generate(CUR);
        @SuppressWarnings("unchecked")
        Map<String, Object> rev = (Map<String, Object>) out.get("reversedPrior");
        assertEquals(PREV, rev.get("period"));
        assertEquals(1, rev.get("count"), "上期一张 POSTED 凭证被红冲");

        // 红冲链：新凭证 REVERSES_ID 指向上期凭证
        @SuppressWarnings("unchecked")
        List<String> revNos = (List<String>) rev.get("vouchers");
        assertEquals(1, revNos.size());
        Map<String, Object> revRow = jdbc.queryForMap(
                "SELECT ID, REVERSES_ID FROM erp_fin_voucher WHERE VOUCHER_NO=?", revNos.get(0));
        assertEquals(prev.get("voucherId"), revRow.get("REVERSES_ID"), "红冲链锚定上期凭证");

        // 上期台账标记被冲回
        List<FxReval> prevRows = revalDao.selectByPeriod(PREV);
        assertNotNull(prevRows.get(0).getReversedBy(), "台账 REVERSED_BY 已写");
    }

    // ---------- 4. 上期 DRAFT 作废（无可冲回直接清理）----------

    @Test
    void priorDraftVoidedNotReversed() {
        // 上期生成后未审核（DRAFT）
        seedAp("G4", "7.100000");
        Map<String, Object> prev = revalService.generate(PREV);

        Map<String, Object> out = revalService.generate(CUR);
        assertEquals(1, out.get("voidedPriorDrafts"), "上期草稿被作废");
        @SuppressWarnings("unchecked")
        Map<String, Object> rev = (Map<String, Object>) out.get("reversedPrior");
        assertEquals(0, rev.get("count"), "草稿不走红冲");

        // 上期凭证已作废（软删 DEL_FLAG=1，detail 会 422，直查库确认）
        String st = jdbc.queryForObject(
                "SELECT CONCAT(STATUS,'/',DEL_FLAG) FROM erp_fin_voucher WHERE ID=?",
                String.class, prev.get("voucherId"));
        assertTrue("DRAFT/1".equals(st) || st == null, "上期草稿已清理：" + st);
    }

    // ---------- 5. 失败整体回滚（缺期末汇率）----------

    @Test
    void generateFailureRollsBackEverything() {
        // JPY 发票无 209910 期末价 → 阻断
        FinApInvoice jpy = new FinApInvoice();
        jpy.setId("GEN-AP-JPY");
        jpy.setInvoiceNo("GEN-JPY");
        jpy.setSupplierId("SUP-GEN");
        jpy.setSupplierName("GEN 供应商");
        jpy.setInvoiceDate(LocalDate.of(2099, 8, 1));
        jpy.setCurrency("JPY");
        jpy.setBookingRate(new BigDecimal("0.050000"));
        jpy.setTotalAmount(new BigDecimal("100000"));
        jpy.setPaidAmount(BigDecimal.ZERO);
        jpy.setStatus(FinApInvoice.ST_POSTED);
        apDao.insert(jpy);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> revalService.generate(CUR));
        assertTrue(ex.getMessage().contains("JPY"), ex.getMessage());
        // 无台账残留、无凭证残留（purge 已清 REVAL 凭证，此处计数应为 0）
        assertEquals(0, revalDao.selectCountByPeriod(CUR), "阻断后无台账残留");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='REVAL'",
                Integer.class).intValue(), "阻断后无凭证残留");
    }

    // ---------- 6. 三态链：DRAFT → AUDITED → POSTED（task 5.1）----------

    @Test
    void revalVoucherLifecycleSelfAuditBlocked() {
        // 制单人 = junit（显式登录保证 C-4.6-07 断言有效）
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                        "junit", null, "ROLE_ADMIN"));
        seedAp("G6", "7.120000");
        Map<String, Object> out = revalService.generate(CUR);
        String vid = (String) out.get("voucherId");

        // 制单人自审阻断（C-4.6-07 职责分离）
        ServiceException ex = assertThrows(ServiceException.class, () -> voucherService.audit(vid));
        assertTrue(ex.getMessage().contains("同一人"), ex.getMessage());

        // 换财务主管审核 → AUDITED → 过账 → POSTED
        loginFinanceMgr();
        voucherService.audit(vid);
        assertEquals("AUDITED", String.valueOf(voucherService.detail(vid).get("status")));
        voucherService.post(vid);
        assertEquals("POSTED", String.valueOf(voucherService.detail(vid).get("status")));
    }

    @Test
    void revalVoucherPostWrites6604Balance() {
        seedAp("G7", "7.120000");
        Map<String, Object> out = revalService.generate(CUR);
        String vid = (String) out.get("voucherId");

        loginFinanceMgr();
        voucherService.audit(vid);
        // 过账期间 209910 须 OPEN（沿用期间种子口径）
        jdbc.update("INSERT IGNORE INTO erp_fin_period (ID,PERIOD,STATUS,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES ('per-rvt-209910','209910','OPEN','junit','0',0)");
        voucherService.post(vid);

        // 6604 贷方 800.00 入总账（汇兑收益）
        Map<String, Object> bal = jdbc.queryForMap(
                "SELECT COALESCE(DR_TOTAL,0) dr, COALESCE(CR_TOTAL,0) cr FROM erp_fin_balance "
                + "WHERE PERIOD=? AND ACCOUNT_CODE='6604'", CUR);
        assertEquals(0, new BigDecimal("800.00")
                .compareTo(new BigDecimal(String.valueOf(bal.get("cr")))), "6604 贷方 800.00");
    }

    private void loginAuditor() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                        "auditor", null, "ROLE_FINANCE_MGR"));
    }

    private void loginFinanceMgr() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                        "fin-mgr", null, "ROLE_FIN_MGR"));
    }
}
