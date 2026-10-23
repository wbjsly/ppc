package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.ApPlanDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.entity.fin.ApPlan;
import com.erp.entity.fin.FinApInvoice;
import com.erp.service.SysParamService;
import com.erp.service.fin.ApAgingService;
import com.erp.service.fin.ApForecastService;
import com.erp.service.fin.ApPlanService;
import com.erp.service.fin.ThreeWayMatchService;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-ap-management：应付管理 DbTest（spec ap-management）。
 * 账期校验阻断 / DUE_DATE 自动计算 / 账龄六段落段 / 超期 / 未清为零不计 /
 * 计划创建审批 / 超计划阻断 / 资金缺口预测 / 存量 NULL 到期日跳过账龄。
 */
@SpringBootTest
class ApManagementDbTest {

    @Autowired
    private ApAgingService agingService;
    @Autowired
    private ApPlanService planService;
    @Autowired
    private ApForecastService forecastService;
    @Autowired
    private ThreeWayMatchService matchService;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private FinApInvoiceDao apDao;
    @Autowired
    private ApPlanDao planDao;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String SUP = "SUP-APT-001";
    /** 测试期间（未来隔离） */
    private static final String FUT = "209912";
    private static final LocalDate BASE = LocalDate.of(2099, 7, 31);

    @BeforeEach
    void setUp() {
        purge();
        login();
        sysParamService.setValue("AR_AGING_BUCKETS", "30,60,90,180,360", "STRING", "FIN", "junit", "junit");
        // 测试供应商（账期 30 天）
        jdbc.update("INSERT IGNORE INTO erp_mdm_supplier (ID,SUPPLIER_CODE,SUPPLIER_NAME,PAYMENT_TERMS,"
                + "STATUS,CREATE_BY,DEL_FLAG,VER_NO) VALUES (?,?,?,'30','QUALIFIED','junit','0',0)",
                SUP, "SUPAPT", "APT 供应商");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_payment_plan WHERE PERIOD=?", FUT);
        jdbc.update("DELETE FROM erp_fin_ap_invoice WHERE INVOICE_NO LIKE 'APT-%'");
        jdbc.update("DELETE FROM erp_mdm_supplier WHERE ID IN ('" + SUP + "','SUP-APT-NOTERM')");
        jdbc.update("DELETE FROM erp_mdm_exchange_rate WHERE ID='fx-apt-usd'");
    }

    /** 造已过账应付（dueDate 可空模拟存量未补录） */
    private void seedAp(String no, String dueDate, String total, String paid) {
        jdbc.update("INSERT INTO erp_fin_ap_invoice (ID,INVOICE_NO,SUPPLIER_ID,SUPPLIER_NAME,"
                + "INVOICE_DATE,DUE_DATE,CURRENCY,TOTAL_AMOUNT,PAID_AMOUNT,STATUS,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES (?,?,?,?,'2099-01-15',?,'CNY',?,?,'POSTED','junit','0',0)",
                "apt-" + no, no, SUP, "APT 供应商", dueDate, total, paid);
    }

    // ---------- 1. 账期校验阻断 + DUE_DATE 自动计算 ----------

    @Test
    void termMissingBlocksInvoice() {
        // 无账期供应商 → 登记发票 422
        jdbc.update("INSERT IGNORE INTO erp_mdm_supplier (ID,SUPPLIER_CODE,SUPPLIER_NAME,PAYMENT_TERMS,"
                + "STATUS,CREATE_BY,DEL_FLAG,VER_NO) VALUES ('SUP-APT-NOTERM','SUPNT','无账期供应商',NULL,"
                + "'QUALIFIED','junit','0',0)");
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("invoiceNo", "APT-TERM-01");
        payload.put("supplierId", "SUP-APT-NOTERM");
        payload.put("invoiceDate", "2099-01-15");
        payload.put("totalAmount", 1000);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> matchService.createInvoice(payload));
        assertTrue(ex.getMessage().contains("账期"), "无账期阻断并提示维护账期");
    }

    @Test
    void dueDateAutoCalc() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("invoiceNo", "APT-DUE-01");
        payload.put("supplierId", SUP);
        payload.put("invoiceDate", "2099-01-15");
        payload.put("totalAmount", 5000);
        FinApInvoice inv = matchService.createInvoice(payload);
        // 账期 30 天 → 2099-01-15 + 30 = 2099-02-14
        assertEquals(LocalDate.of(2099, 2, 14), inv.getDueDate(), "DUE_DATE=发票日+账期30天");
    }

    // ---------- 1.5 外币发票记账汇率固化（add-fx-revaluation D1）----------

    @Test
    void usdInvoiceFixesBookingRate() {
        // 造 USD/CNY 中间价（2099 全年生效）
        jdbc.update("INSERT IGNORE INTO erp_mdm_exchange_rate (ID,BASE_CCY,QUOTE_CCY,RATE_TYPE,"
                + "EFFECTIVE_DATE,EXPIRE_DATE,RATE,SOURCE_FILE_NO,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES ('fx-apt-usd','USD','CNY','MIDDLE','2099-01-01','2099-12-31',7.120000,"
                + "'央行公告APT','junit','0',0)");
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("invoiceNo", "APT-FX-01");
        payload.put("supplierId", SUP);
        payload.put("invoiceDate", "2099-01-15");
        payload.put("currency", "USD");
        payload.put("totalAmount", 1000);
        FinApInvoice inv = matchService.createInvoice(payload);
        assertEquals("USD", inv.getCurrency(), "币种 USD");
        assertEquals(0, new BigDecimal("7.120000").compareTo(inv.getBookingRate()),
                "外币发票按发票日中间价固化记账汇率");
        // 固化后修改汇率主数据不影响该发票（历史事实不可篡改）
        jdbc.update("UPDATE erp_mdm_exchange_rate SET RATE=9.999999 WHERE ID='fx-apt-usd'");
        assertEquals(0, new BigDecimal("7.120000").compareTo(
                apDao.selectById(inv.getId()).getBookingRate()), "记账汇率不随后续汇率变动");
    }

    @Test
    void usdInvoiceMissingRateBlocks() {
        // 无 JPY 生效汇率 → 登记 JPY 发票 422 阻断（C-4.6-03）
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("invoiceNo", "APT-FX-02");
        payload.put("supplierId", SUP);
        payload.put("invoiceDate", "2099-01-15");
        payload.put("currency", "JPY");
        payload.put("totalAmount", 1000);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> matchService.createInvoice(payload));
        assertTrue(ex.getMessage().contains("汇率主数据缺失"), "缺汇率 L1 阻断并提示维护主数据");
    }

    @Test
    void cnyInvoiceBookingRateOneNoRateQuery() {
        // CNY 发票不查汇率主数据：即便无 CNY/CNY 中间价也不阻断，BOOKING_RATE=1
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("invoiceNo", "APT-FX-03");
        payload.put("supplierId", SUP);
        payload.put("invoiceDate", "2099-01-15");
        payload.put("currency", "CNY");
        payload.put("totalAmount", 1000);
        FinApInvoice inv = matchService.createInvoice(payload);
        assertEquals(0, BigDecimal.ONE.compareTo(inv.getBookingRate()), "CNY 记账汇率置 1");
    }

    // ---------- 2. 账龄六段落段 + 超期 + 未清为零不计 + NULL 跳过 ----------

    @Test
    void agingSixBuckets() {
        // 相对基准日 2099-07-31 的逾期天数落段
        seedAp("APT-B-01", "2099-07-31", "100", "0");    // 0 天   → 0-30
        seedAp("APT-B-02", "2099-06-30", "200", "0");    // 31 天  → 31-60
        seedAp("APT-B-03", "2099-05-02", "400", "0");    // 90 天  → 61-90
        seedAp("APT-B-04", "2099-05-01", "800", "0");    // 91 天  → 91-180
        seedAp("APT-B-05", "2099-01-31", "1600", "0");   // 181 天 → 181-360
        seedAp("APT-B-06", "2098-07-01", "3200", "0");   // 365 天 → 360+
        Map<String, Object> a = agingService.aging(SUP, BASE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) a.get("buckets");
        assertEquals(6, buckets.size(), "六段");
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) buckets.get(0).get("base")), "0-30");
        assertEquals(0, new BigDecimal("800.00").compareTo((BigDecimal) buckets.get(3).get("base")), "91-180");
        assertEquals(0, new BigDecimal("3200.00").compareTo((BigDecimal) buckets.get(5).get("base")), "360+");
    }

    @Test
    void agingOverdueAndZeroUnpaidExcluded() {
        seedAp("APT-O-01", "2099-07-01", "7000", "0");    // 30 天 → 0-30（未超期）
        seedAp("APT-O-02", "2099-05-01", "3000", "0");    // 91 天 → 91-180（超期）
        seedAp("APT-O-03", "2099-07-01", "9999", "9999"); // 已付清 → 不计
        Map<String, Object> a = agingService.aging(SUP, BASE);
        assertEquals(0, new BigDecimal("10000.00").compareTo((BigDecimal) a.get("total")),
                "已付清不计账龄（7000+3000）");
        // 超期（>首边界30天）= 3000 / 10000 = 0.30
        assertEquals(0, new BigDecimal("0.3000").compareTo((BigDecimal) a.get("overdueRatio")),
                "超期占比 0.30");
    }

    @Test
    void agingSkipsNullDueDate() {
        seedAp("APT-N-01", null, "5000", "0");   // 存量未补录 → 跳过
        Map<String, Object> a = agingService.aging(SUP, BASE);
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) a.get("total")), "NULL 到期日跳过");
        assertEquals(1, a.get("nullDueCount"), "跳过计数=1");
    }

    @Test
    void backfillDueDateFills() {
        seedAp("APT-BF-01", null, "5000", "0");  // 供应商账期 30 天
        Map<String, Object> out = agingService.backfillDueDate();
        assertEquals(1, out.get("updated"), "回填 1 张");
        FinApInvoice inv = apDao.selectById("apt-APT-BF-01");
        assertEquals(LocalDate.of(2099, 2, 14), inv.getDueDate(), "回填=发票日+30天");
    }

    // ---------- 3. 付款计划闭环 ----------

    @Test
    void planLifecycle() {
        ApPlan req = new ApPlan();
        req.setPeriod(FUT);
        req.setPlanTotal(new BigDecimal("100000"));
        Map<String, Object> created = planService.create(req);
        String planId = (String) created.get("planId");
        // 重复创建 422（PERIOD 唯一）
        ApPlan dup = new ApPlan();
        dup.setPeriod(FUT);
        dup.setPlanTotal(new BigDecimal("50000"));
        assertThrows(ServiceException.class, () -> planService.create(dup));
        // 提交 → 审批通过 → 关闭
        planService.submit(planId);
        planService.onApprovalResult(planId, true, null);
        assertEquals(ApPlan.ST_APPROVED, planDao.selectById(planId).getStatus(), "审批通过 APPROVED");
        planService.close(planId);
        assertEquals(ApPlan.ST_CLOSED, planDao.selectById(planId).getStatus(), "关闭 CLOSED");
    }

    @Test
    void overPlanBlocksPayment() {
        ApPlan req = new ApPlan();
        req.setPeriod(FUT);
        req.setPlanTotal(new BigDecimal("10000"));
        String planId = (String) planService.create(req).get("planId");
        planService.submit(planId);
        planService.onApprovalResult(planId, true, null);
        // 计划 1 万，执行 8 千后，再付 3 千 → 超计划 422
        planService.addExecuted(FUT, new BigDecimal("8000"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.assertWithinPlan(FUT, new BigDecimal("3000")));
        assertTrue(ex.getMessage().contains("超付款计划"), "超计划阻断");
        // 计划内金额放行
        assertDoesNotThrow(() -> planService.assertWithinPlan(FUT, new BigDecimal("2000")));
    }

    // ---------- 4. 资金缺口预测 ----------

    @Test
    void forecastGap() {
        // 未来 30 天内到期未清 5 万；银行余额清零后缺口为负
        seedAp("APT-F-01", BASE.plusDays(10).toString(), "50000", "0");
        jdbc.update("UPDATE erp_fin_bank_account SET BALANCE=0 WHERE STATUS='ACTIVE'");
        Map<String, Object> f = forecastService.forecast(BASE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> windows = (List<Map<String, Object>>) f.get("windows");
        Map<String, Object> w30 = windows.stream()
                .filter(w -> Integer.valueOf(30).equals(w.get("days"))).findFirst().orElseThrow();
        // 累积口径含逾期且为全局聚合（共享库其他发票/预付会影响绝对值），故验下界与公式自洽而非硬编码
        BigDecimal apDue = (BigDecimal) w30.get("apDue");
        assertTrue(apDue.compareTo(new BigDecimal("50000.00")) >= 0, "30 天到期含本测试 5 万（≥）");
        BigDecimal bank = (BigDecimal) w30.get("bank");
        BigDecimal need = (BigDecimal) w30.get("need");
        BigDecimal gap = (BigDecimal) w30.get("gap");
        assertEquals(0, bank.subtract(need).compareTo(gap), "缺口=银行−需求");
        assertEquals(0, new BigDecimal("0.00").compareTo(bank), "银行余额已清零");
        assertTrue(gap.signum() < 0, "需求>银行 → 缺口为负");
    }
}
