package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.AmortPlanDao;
import com.erp.dao.fin.DeferredDocDao;
import com.erp.dao.fin.PrepaidDocDao;
import com.erp.entity.fin.AmortPlan;
import com.erp.service.fin.AmortService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 预提待摊 DbTest（add-expense-reimbursement：spec prepaid-amortization 4.1~4.4 + 结账⑨素材）。
 * 共享库隔离：测试期间统一 2099xx（余额行按期间可清）、junit 前缀造数 + purge（四次变更先例）。
 */
@SpringBootTest
class AmortPlanDbTest {

    private static final String PERIOD = "209901";
    private static final String YEAR_END = "209912";
    private static final String BANK = "junit-bank-amort";

    @Autowired
    private AmortService amortService;
    @Autowired
    private PrepaidDocDao prepaidDao;
    @Autowired
    private DeferredDocDao deferredDao;
    @Autowired
    private AmortPlanDao planDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_FIN_MGR"));
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        // 凭证（DRAFT 与已过账均清；余额行按 2099 期间+相关科目整批清，测试专属期间）
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE IN ('PREPAY','AMORT','DEFER','PSETTLE')");
        jdbc.update("DELETE FROM erp_fin_balance_journal WHERE VOUCHER_ID NOT IN "
                + "(SELECT ID FROM erp_fin_voucher)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('PREPAY','AMORT','DEFER','PSETTLE')");
        jdbc.update("DELETE FROM erp_fin_balance WHERE PERIOD LIKE '2099%' AND ACCOUNT_CODE IN "
                + "('2251','1801','1002','2202','660202','660203','660204','660205','660206')");
        jdbc.update("DELETE FROM erp_fin_amort_plan WHERE CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_fin_prepaid_doc WHERE CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_fin_deferred_doc WHERE CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_fin_bank_account WHERE ID = ?", BANK);
        jdbc.update("DELETE FROM erp_fin_period WHERE PERIOD = ? AND CREATE_BY = 'junit'", PERIOD);
    }

    private void seedBank(String balance) {
        jdbc.update("INSERT INTO erp_fin_bank_account (ID, ACCOUNT_NAME, ACCOUNT_NO, CURRENCY, "
                + "BALANCE, FC_BALANCE, BOOKING_RATE, STATUS, CREATE_BY, CREATE_DATE, VER_NO, DEL_FLAG) "
                + "VALUES (?, 'Junit 摊销户', ?, 'CNY', ?, ?, 1, 'ACTIVE', 'junit', NOW(), 0, '0')",
                BANK, "JUNITAMT" + (System.nanoTime() % 10000000), balance, balance);
    }

    private Map<String, Object> prepaidPayload(String amount, String period) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expenseAccountCode", "660203");
        m.put("period", period);
        m.put("amount", amount);
        m.put("remark", "junit 预提");
        return m;
    }

    private Map<String, Object> deferredPayload(String total, int months, String start) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", "junit 房租待摊");
        m.put("totalAmount", total);
        m.put("amortMonths", months);
        m.put("startPeriod", start);
        m.put("expenseAccountCode", "660203");
        return m;
    }

    private List<Map<String, Object>> plansOf(String docId) {
        return planDao.selectByDoc(docId).stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", p.getPeriod());
            m.put("amount", p.getAmount());
            m.put("genStatus", p.getGenStatus());
            m.put("voucherId", p.getVoucherId());
            return m;
        }).toList();
    }

    // ================================================================
    // 4.1 登记与计划（四场景：尾差 / 超12月L4 / 非法参数 / L1 结构校验）
    // ================================================================

    @Test
    void deferredStraightLineResidualToLastPeriod() {
        Map<String, Object> out = amortService.registerDeferred(deferredPayload("100000", 3, PERIOD));
        assertEquals(Boolean.TRUE, out.get("l4Hint") == null, "3 期无 L4");
        List<Map<String, Object>> plans = plansOf((String) out.get("id"));
        assertEquals(3, plans.size());
        assertEquals(0, new BigDecimal("33333.33").compareTo((BigDecimal) plans.get(0).get("amount")));
        assertEquals(0, new BigDecimal("33333.33").compareTo((BigDecimal) plans.get(1).get("amount")));
        assertEquals(0, new BigDecimal("33333.34").compareTo((BigDecimal) plans.get(2).get("amount")),
                "尾差 0.01 并入末期（FR-4.6-8-2）");
        BigDecimal sum = plans.stream().map(p -> (BigDecimal) p.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("100000").compareTo(sum), "L1 合计=登记总额");
    }

    @Test
    void deferredOver12MonthsL4Hint() {
        Map<String, Object> out = amortService.registerDeferred(deferredPayload("240000", 24, PERIOD));
        assertNotNull(out.get("l4Hint"), "24 月 → L4 提示（BR-4.6-05）");
        assertTrue(String.valueOf(out.get("l4Hint")).contains("长期待摊"), "L4 文案");
        assertEquals(24, plansOf((String) out.get("id")).size());
    }

    @Test
    void illegalParamsBlocked() {
        // 摊销月数 0 → 422
        ServiceException ex = assertThrows(ServiceException.class, () ->
                amortService.registerDeferred(deferredPayload("100000", 0, PERIOD)));
        assertTrue(ex.getMessage().contains("≥ 1"), ex.getMessage());
        // 负金额 → 422
        ServiceException ex2 = assertThrows(ServiceException.class, () ->
                amortService.registerDeferred(deferredPayload("-5", 3, PERIOD)));
        assertTrue(ex2.getMessage().contains("大于 0"), ex2.getMessage());
        // 预提期间格式非法 → 422
        ServiceException ex3 = assertThrows(ServiceException.class, () ->
                amalgamatePrepaidIllegal());
        assertTrue(ex3.getMessage().contains("yyyyMM"), ex3.getMessage());
    }

    private void amalgamatePrepaidIllegal() {
        amortService.registerPrepaid(prepaidPayload("5000", "999"));
    }

    @Test
    void prepaidRegistersSinglePeriodPlan() {
        Map<String, Object> out = amortService.registerPrepaid(prepaidPayload("8000", PERIOD));
        List<Map<String, Object>> plans = plansOf((String) out.get("id"));
        assertEquals(1, plans.size(), "预提单期计划");
        assertEquals(PERIOD, plans.get(0).get("period"));
        assertEquals(0, new BigDecimal("8000").compareTo((BigDecimal) plans.get(0).get("amount")));
    }

    // ================================================================
    // 4.2 手动触发与幂等（三场景）
    // ================================================================

    @Test
    void generateCreatesDraftVouchersIdempotent() {
        String preId = (String) amortService.registerPrepaid(prepaidPayload("8000", PERIOD)).get("id");
        String defId = (String) amortService.registerDeferred(deferredPayload("100000", 3, PERIOD)).get("id");
        // 首触发：1 预提 + 1 待摊（本期首行）= 2 张 DRAFT
        Map<String, Object> first = amortService.generate(PERIOD);
        assertEquals(2, first.get("generated"), "首触发生成数");
        for (Map<String, Object> p : plansOf(defId)) {
            if (PERIOD.equals(p.get("period"))) {
                assertEquals("GENERATED", p.get("genStatus"));
                assertNotNull(p.get("voucherId"));
            }
        }
        String anyVoucher = (String) plansOf(preId).get(0).get("voucherId");
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_voucher WHERE ID = ?", String.class, anyVoucher),
                "计划凭证 DRAFT（FR-4.6-8-4 制单≠审核）");
        // 二触发：幂等 0 行、跳过 2
        Map<String, Object> second = amortService.generate(PERIOD);
        assertEquals(0, second.get("generated"), "二次触发 0 行");
        assertEquals(2L, ((Number) second.get("skipped")).longValue(), "跳过已生成行");
        // 待摊单全生成 → COMPLETED（本期仅 1 行，共 3 行 → 未完成）
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_deferred_doc WHERE ID = ?", String.class, defId),
                "尚有未生成期间，仍 OPEN");
    }

    @Test
    void generateNoRowsIsNoop() {
        Map<String, Object> out = amortService.generate("209905");
        assertEquals(0, out.get("generated"));
        assertEquals(0L, ((Number) out.get("skipped")).longValue());
    }

    @Test
    void generateClosedPeriodBlocked() {
        amortService.registerPrepaid(prepaidPayload("3000", PERIOD));
        jdbc.update("INSERT INTO erp_fin_period (ID, PERIOD, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'CLOSED', 'junit', '0', 0)", "junit-" + PERIOD, PERIOD);
        try {
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    amortService.generate(PERIOD));
            assertTrue(ex.getMessage().contains("已关闭"), ex.getMessage());
        } finally {
            jdbc.update("DELETE FROM erp_fin_period WHERE PERIOD = ? AND CREATE_BY = 'junit'", PERIOD);
        }
    }

    // ================================================================
    // 4.3 实际冲销与差异（三场景）
    // ================================================================

    private String settlePayload(String actual) {
        return actual;
    }

    private Map<String, Object> settleMap(String actual) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actualAmount", actual);
        m.put("settleMode", "PAYMENT");
        m.put("bankAccountId", BANK);
        m.put("payDate", "2099-01-20");
        return m;
    }

    @Test
    void settleVarianceOverToleranceMarksRate() {
        seedBank("100000");
        String id = (String) amortService.registerPrepaid(prepaidPayload("15000", PERIOD)).get("id");
        Map<String, Object> out = amortService.settlePrepaid(id, settleMap("15300"));
        assertEquals(Boolean.TRUE, out.get("overTolerance"), "差异率 2% > 0.5%（C-4.6-16）");
        // 冲销凭证三行 + 差异行摘要含差异率标记
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT l.ACCOUNT_CODE, l.DIRECTION, l.SUMMARY FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
                        + "WHERE v.VOUCHER_TYPE = 'PSETTLE' ORDER BY FIELD(l.DIRECTION,'DR','CR')");
        assertEquals(3, lines.size(), "冲销凭证三行");
        assertTrue(lines.stream().anyMatch(l -> String.valueOf(l.get("SUMMARY")).contains("差异率")),
                "差异行/凭证摘要含差异率标记");
        // 借贷平衡
        Map<String, Object> bc = jdbc.queryForMap(
                "SELECT COALESCE(SUM(CASE WHEN l.DIRECTION='DR' THEN l.AMOUNT ELSE 0 END),0) D, "
                        + "COALESCE(SUM(CASE WHEN l.DIRECTION='CR' THEN l.AMOUNT ELSE 0 END),0) C "
                        + "FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                        + "WHERE v.VOUCHER_TYPE='PSETTLE'");
        assertEquals(0, new BigDecimal("15300").compareTo((BigDecimal) bc.get("D")), "借方合计 15300");
        assertEquals(0, new BigDecimal("15300").compareTo((BigDecimal) bc.get("C")), "贷方合计 15300");
    }

    @Test
    void settleWithinToleranceNoOverFlag() {
        seedBank("100000");
        String id = (String) amortService.registerPrepaid(prepaidPayload("15000", PERIOD)).get("id");
        Map<String, Object> out = amortService.settlePrepaid(id, settleMap("15050"));
        assertEquals(Boolean.FALSE, out.get("overTolerance"), "0.33% ≤ 0.5% 无超容差标记");
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT l.SUMMARY FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID WHERE v.VOUCHER_TYPE='PSETTLE'");
        assertEquals(3, lines.size(), "差异行恒生成保借贷平衡");
        assertTrue(lines.stream().noneMatch(l -> String.valueOf(l.get("SUMMARY")).contains("差异率")),
                "容差内差异行无差异率超容差标记");
    }

    @Test
    void settleRepeatBlocked() {
        seedBank("100000");
        String id = (String) amortService.registerPrepaid(prepaidPayload("6000", PERIOD)).get("id");
        amortService.settlePrepaid(id, settleMap("6000"));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                amortService.settlePrepaid(id, settleMap("6000")));
        assertTrue(ex.getMessage().contains("已冲销"), ex.getMessage());
        // ON_ACCOUNT（收票挂应付，无现金）
        String id2 = (String) amortService.registerPrepaid(prepaidPayload("4000", PERIOD)).get("id");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actualAmount", "4000");
        m.put("settleMode", "ON_ACCOUNT");
        m.put("payDate", "2099-01-21");
        Map<String, Object> out = amortService.settlePrepaid(id2, m);
        assertEquals("SETTLED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_prepaid_doc WHERE ID = ?", String.class, id2));
        assertNotNull(out.get("voucherNo"));
    }

    // ================================================================
    // 4.4 年末余额检查（L4 三场景：非零黄标 / 清零绿 / 待摊勾稽不等黄标）
    // ================================================================

    @Test
    void yearEndPrepaidOutstandingYellow() {
        seedBank("100000");
        String id = (String) amortService.registerPrepaid(prepaidPayload("8000", PERIOD)).get("id");
        amortService.settlePrepaid(id, settleMap("8000")); // 过账借 2251 8000（计提侧未过账 → 累计非 0）
        Map<String, Object> check = amortService.yearEndCheck(YEAR_END);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> prepaid = items.stream()
                .filter(i -> "prepaidYearEnd".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, prepaid.get("ok"), "预提年末余额非零 → L4 黄标");
        assertTrue(String.valueOf(prepaid.get("detail")).contains("8000"), "黄标明细含余额");
        assertEquals("L4", prepaid.get("level"), "级别 L4 提示非阻断");
    }

    @Test
    void yearEndClearedGreen() {
        // 无预提活动：2251/1801 累计均为 0 → 双绿
        Map<String, Object> check = amortService.yearEndCheck(YEAR_END);
        assertEquals(Boolean.TRUE, check.get("allOk"), "清零/勾稽一致 → 放行");
    }

    @Test
    void yearEndDeferredReconMismatchYellow() {
        String defId = (String) amortService.registerDeferred(deferredPayload("240000", 12, PERIOD)).get("id");
        // 1801 挂账已过账 240,000；人为把本期计划行标 GENERATED（绕过凭证）→ 剩余 220,000 ≠ 余额 240,000
        jdbc.update("UPDATE erp_fin_amort_plan SET GEN_STATUS = 'GENERATED' "
                + "WHERE DOC_ID = ? AND PERIOD = ?", defId, PERIOD);
        Map<String, Object> check = amortService.yearEndCheck(YEAR_END);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> def = items.stream()
                .filter(i -> "deferredRecon".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, def.get("ok"), "待摊余额 vs 计划剩余不等 → 黄标");
        assertTrue(String.valueOf(def.get("detail")).contains("240000"), "差额明细：余额 240000");
        assertTrue(String.valueOf(def.get("detail")).contains("220000"), "差额明细：剩余 220000");
    }

    // ================================================================
    // 5.1 结账第⑨项（两分支）
    // ================================================================

    @Autowired
    private com.erp.service.fin.FinPeriodService periodService;

    @Test
    @SuppressWarnings("unchecked")
    void checklistBlockedByUngeneratedPlan() {
        amortService.registerPrepaid(prepaidPayload("5000", PERIOD));
        Map<String, Object> check = periodService.checklist(PERIOD);
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> item = items.stream()
                .filter(i -> "pendingAmortPlan".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, item.get("ok"), "存在未生成计划行 → ⑨阻断");
        assertTrue(String.valueOf(item.get("detail")).contains("生成本期凭证"), "提示动作");
    }

    @Test
    @SuppressWarnings("unchecked")
    void checklistPassesWithoutPlanRows() {
        // 本期无计划行 → ⑨按需放行
        Map<String, Object> check = periodService.checklist(PERIOD);
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> item = items.stream()
                .filter(i -> "pendingAmortPlan".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, item.get("ok"), "无计划行放行");
    }
}
