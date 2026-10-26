package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArProvisionDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArProvision;
import com.erp.service.SysParamService;
import com.erp.service.fin.ArAgingService;
import com.erp.service.fin.ArConfirmService;
import com.erp.service.fin.ArProvisionService;
import com.erp.service.fin.FinPeriodService;
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
 * add-ar-management：应收管理 DbTest（spec ar-management + gl-management delta 第⑤项）。
 * 确认锚点（未确认不计账龄）/ 六段落段 / 超90占比 / 环比 / 试算不落库 /
 * 确认凭证 6702+1232 / 下期冲回再重提 / 科目缺失阻断 / 参数调整不回溯 / 结账第⑤项阻断。
 */
@SpringBootTest
class ArManagementDbTest {

    @Autowired
    private ArConfirmService confirmService;
    @Autowired
    private ArAgingService agingService;
    @Autowired
    private ArProvisionService provisionService;
    @Autowired
    private FinPeriodService periodService;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private ArInvoiceDao arDao;
    @Autowired
    private ArProvisionDao provisionDao;
    @Autowired
    private JdbcTemplate jdbc;

    /** 测试客户与基准日（未来隔离，避免共享库历史数据污染） */
    private static final String CUST = "CUST-AGT-001";
    private static final LocalDate BASE = LocalDate.of(2099, 7, 31);
    private static final String P1 = "209907";
    private static final String P2 = "209908";

    @BeforeEach
    void setUp() {
        purge();
        login();
        // 参数回默认（六段 + 六比例），防他测改写
        sysParamService.setValue("AR_AGING_BUCKETS", "30,60,90,180,360", "STRING", "FIN", "junit", "junit");
        sysParamService.setValue("AR_BAD_DEBT_RATES", "0.005,0.01,0.02,0.05,0.10,0.30", "STRING", "FIN", "junit", "junit");
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
        // 计提关联凭证（含冲回）与台账
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "LEFT JOIN erp_fin_ar_provision p1 ON v.ID=p1.VOUCHER_ID "
                + "LEFT JOIN erp_fin_ar_provision p2 ON v.REVERSES_ID=p2.VOUCHER_ID "
                + "WHERE p1.ID IS NOT NULL OR p2.ID IS NOT NULL");
        jdbc.update("DELETE v FROM erp_fin_voucher v "
                + "LEFT JOIN erp_fin_ar_provision p1 ON v.ID=p1.VOUCHER_ID "
                + "LEFT JOIN erp_fin_ar_provision p2 ON v.REVERSES_ID=p2.VOUCHER_ID "
                + "WHERE p1.ID IS NOT NULL OR p2.ID IS NOT NULL");
        jdbc.update("DELETE FROM erp_fin_ar_provision WHERE CUSTOMER_ID=?", CUST);
        // 测试应收
        jdbc.update("DELETE FROM erp_fin_ar_invoice WHERE AR_NO LIKE 'AGT-%'");
    }

    /** 造应收（confirmed=true 直接置确认标记，跳过确认动作） */
    private void seedAr(String no, String dueDate, String amount, boolean confirmed) {
        jdbc.update("INSERT INTO erp_fin_ar_invoice "
                + "(ID,AR_NO,CUSTOMER_ID,CUSTOMER_CODE,CUSTOMER_NAME,INVOICE_DATE,DUE_DATE,"
                + "AMOUNT,PAID_AMOUNT,RED_AMOUNT,STATUS,CONFIRM_FLAG,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES (?,?,?,?,?,?,?,?,0,0,'UNPAID',?,?,'0',0)",
                "agt-" + no, no, CUST, "AGTC", "AGT 客户", dueDate, dueDate, amount,
                confirmed ? "1" : "0", "junit");
    }

    // ---------- 1. 确认锚点：未确认不计账龄 ----------

    @Test
    void confirmAnchorExcludesUnconfirmedFromAging() {
        seedAr("AGT-C-01", "2099-05-02", "20000", false);   // 未确认，超90
        seedAr("AGT-C-02", "2099-07-01", "5000", true);     // 已确认，0-30
        Map<String, Object> a = agingService.aging(CUST, BASE);
        BigDecimal total = (BigDecimal) a.get("total");
        // 只含已确认 5000；未确认 20000 不计
        assertEquals(0, new BigDecimal("5000.00").compareTo(total), "未确认不计账龄");
    }

    @Test
    void confirmSingleAndBatch() {
        seedAr("AGT-C-11", "2099-07-01", "1000", false);
        seedAr("AGT-C-12", "2099-07-01", "2000", false);
        seedAr("AGT-C-13", "2099-07-01", "3000", true);     // 已确认
        String id11 = jdbc.queryForObject(
                "SELECT ID FROM erp_fin_ar_invoice WHERE AR_NO='AGT-C-11'", String.class);
        String id12 = jdbc.queryForObject(
                "SELECT ID FROM erp_fin_ar_invoice WHERE AR_NO='AGT-C-12'", String.class);
        // 单笔确认
        confirmService.confirm(id11);
        assertEquals("1", arDao.selectById(id11).getConfirmFlag(), "单笔确认置标记");
        // 重复确认 422
        assertThrows(ServiceException.class, () -> confirmService.confirm(id11));
        // 批量确认（含已确认的 id13 → skip）
        Map<String, Object> out = confirmService.batchConfirm(List.of(id12,
                jdbc.queryForObject("SELECT ID FROM erp_fin_ar_invoice WHERE AR_NO='AGT-C-13'", String.class)));
        assertEquals(1, out.get("confirmed"), "批量仅确认未确认的 1 张");
        assertEquals(1, ((List<?>) out.get("skipped")).size(), "已确认跳过");
        // 批量上限 200
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add("x" + i);
        }
        assertThrows(ServiceException.class, () -> confirmService.batchConfirm(tooMany));
    }

    // ---------- 2. 六段落段 + 超90占比 + 环比 ----------

    @Test
    void sixBucketAssignment() {
        // 相对基准日 2099-07-31 的逾期天数落段
        seedAr("AGT-B-01", "2099-07-31", "100", true);   // 0 天   → 0-30
        seedAr("AGT-B-02", "2099-06-30", "200", true);   // 31 天  → 31-60
        seedAr("AGT-B-03", "2099-05-02", "400", true);   // 90 天  → 61-90
        seedAr("AGT-B-04", "2099-05-01", "800", true);   // 91 天  → 91-180
        seedAr("AGT-B-05", "2099-01-31", "1600", true);  // 181 天 → 181-360
        seedAr("AGT-B-06", "2098-07-01", "3200", true);  // 365 天 → 360+
        Map<String, Object> a = agingService.aging(CUST, BASE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) a.get("buckets");
        assertEquals(6, buckets.size(), "六段");
        assertEquals(0, new BigDecimal("100.00").compareTo((BigDecimal) buckets.get(0).get("base")), "0-30 段");
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) buckets.get(1).get("base")), "31-60 段");
        assertEquals(0, new BigDecimal("400.00").compareTo((BigDecimal) buckets.get(2).get("base")), "61-90 段");
        assertEquals(0, new BigDecimal("800.00").compareTo((BigDecimal) buckets.get(3).get("base")), "91-180 段");
        assertEquals(0, new BigDecimal("1600.00").compareTo((BigDecimal) buckets.get(4).get("base")), "181-360 段");
        assertEquals(0, new BigDecimal("3200.00").compareTo((BigDecimal) buckets.get(5).get("base")), "360+ 段");
    }

    @Test
    void over90Ratio() {
        seedAr("AGT-O-01", "2099-07-01", "7000", true);   // 0-30
        seedAr("AGT-O-02", "2099-05-01", "3000", true);   // 91-180（超90）
        Map<String, Object> a = agingService.aging(CUST, BASE);
        // 超90 = 3000 / 10000 = 0.30
        assertEquals(0, new BigDecimal("0.3000").compareTo((BigDecimal) a.get("over90Ratio")),
                "超90占比 = 3000/10000 = 0.30");
    }

    @Test
    void momComparison() {
        seedAr("AGT-M-01", "2099-07-01", "500", true);    // 基准日 0-30；上期末(06-30) 30 天仍 0-30
        Map<String, Object> a = agingService.aging(CUST, BASE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mom = (List<Map<String, Object>>) a.get("mom");
        assertEquals(6, mom.size(), "环比六段");
        // 0-30 段本期 500，上期末该卡逾期 30 天仍落 0-30 → prev=500 delta=0
        Map<String, Object> first = mom.get(0);
        assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) first.get("curr")), "本期 0-30");
        assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) first.get("prev")), "上期末 0-30");
    }

    // ---------- 3. 坏账计提：试算不落库 / 确认凭证 / 冲回重提 ----------

    @Test
    void trialDoesNotPersist() {
        // 31-60 段 4 万（rate 0.01 → 400）；91-180 段 2 万（rate 0.05 → 1000）
        seedAr("AGT-T-01", "2099-06-30", "40000", true);  // 31 天
        seedAr("AGT-T-02", "2099-05-01", "20000", true);  // 91 天
        Map<String, Object> t = provisionService.trial(CUST, P1);
        assertEquals(0, new BigDecimal("1400.00").compareTo((BigDecimal) t.get("target")), "目标 1400");
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) t.get("booked")), "已提 0");
        assertEquals(0, new BigDecimal("1400.00").compareTo((BigDecimal) t.get("periodAccrual")), "本期应提 1400");
        // 不落库
        assertEquals(0, provisionDao.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ArProvision>()
                        .eq(ArProvision::getCustomerId, CUST)), "试算不落台账");
    }

    @Test
    void confirmProvisionPostsVoucherAndLedger() {
        seedAr("AGT-P-01", "2099-06-30", "40000", true);  // 31-60 → 400
        seedAr("AGT-P-02", "2099-05-01", "20000", true);  // 91-180 → 1000
        Map<String, Object> out = provisionService.confirmProvision(CUST, P1);
        String vid = (String) out.get("voucherId");
        assertNotNull(vid, "计提凭证生成");
        // 凭证借贷：借 6702 1400 / 贷 1232 1400
        BigDecimal dr6702 = jdbc.queryForObject(
                "SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                        + "WHERE v.ID=? AND l.ACCOUNT_CODE='6702' AND l.DIRECTION='DR'", BigDecimal.class, vid);
        BigDecimal cr1232 = jdbc.queryForObject(
                "SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                        + "WHERE v.ID=? AND l.ACCOUNT_CODE='1232' AND l.DIRECTION='CR'", BigDecimal.class, vid);
        assertEquals(0, new BigDecimal("1400.00").compareTo(dr6702), "借 6702 1400");
        assertEquals(0, new BigDecimal("1400.00").compareTo(cr1232), "贷 1232 1400");
        // 台账 CONFIRMED + 六段快照
        ArProvision p = provisionDao.selectConfirmed(P1, CUST);
        assertNotNull(p, "台账 CONFIRMED");
        assertEquals(ArProvision.ST_CONFIRMED, p.getStatus());
        assertEquals(0, new BigDecimal("1400.00").compareTo(p.getTargetBalance()), "目标 1400");
        assertEquals(0, new BigDecimal("1400.00").compareTo(p.getPeriodAccrual()), "本期计提 1400");
        assertTrue(p.getBucketBase().contains("31-60"), "六段快照含段标签");
        // 重复确认 422
        assertThrows(ServiceException.class, () -> provisionService.confirmProvision(CUST, P1));
    }

    @Test
    void nextPeriodAutoReverseThenReaccrue() {
        seedAr("AGT-R-01", "2099-06-30", "40000", true);  // P1 31-60 → 400
        Map<String, Object> o1 = provisionService.confirmProvision(CUST, P1);
        ArProvision p1 = provisionDao.selectConfirmed(P1, CUST);
        assertEquals("0", p1.getReversedFlag(), "P1 初始未冲回");
        // P2 基数变化：新增 91-180 段 2 万 → 目标 = 400 + 1000 = 1400？先看 P2 时点
        // P2 期间末 2099-08-31；AGT-R-01 逾期 62 天 → 61-90（rate 0.02 → 800）
        seedAr("AGT-R-02", "2099-05-01", "20000", true);  // P2 逾期 122 天 → 91-180（1000）
        Map<String, Object> o2 = provisionService.confirmProvision(CUST, P2);
        // 冲回 P1 凭证
        ArProvision p1After = provisionDao.selectById(p1.getId());
        assertEquals("1", p1After.getReversedFlag(), "P1 已冲回");
        assertNotNull(p1After.getReversedVoucherId(), "冲回凭证锚");
        // 已提 = P1 目标 400
        assertEquals(0, new BigDecimal("400.00").compareTo((BigDecimal) o2.get("booked")), "已提=P1 目标");
        // P2 目标 = 800(61-90) + 1000(91-180) = 1800；本期净额 = 1800-400 = 1400
        assertEquals(0, new BigDecimal("1800.00").compareTo((BigDecimal) o2.get("target")), "P2 目标 1800");
        assertEquals(0, new BigDecimal("1400.00").compareTo((BigDecimal) o2.get("periodAccrual")), "P2 净额 1400");
        // P2 台账
        ArProvision p2 = provisionDao.selectConfirmed(P2, CUST);
        assertEquals(0, new BigDecimal("400.00").compareTo(p2.getBookedBalance()), "台账已提 400");
        assertEquals(0, new BigDecimal("1400.00").compareTo(p2.getPeriodAccrual()), "台账净额 1400");
    }

    // ---------- 4. 科目缺失阻断 / 参数不回溯 / 结账第⑤项 ----------

    @Test
    void missingAccountBlocksConfirm() {
        seedAr("AGT-A-01", "2099-06-30", "40000", true);
        jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='1' WHERE ACCOUNT_CODE='6702'");
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> provisionService.confirmProvision(CUST, P1));
            assertTrue(ex.getMessage().contains("6702"), "阻断列示缺失科目 6702");
        } finally {
            jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE='6702'");
        }
    }

    @Test
    void rateChangeDoesNotRetroactive() {
        seedAr("AGT-N-01", "2099-05-01", "20000", true);  // 91-180 → rate 0.05 → 1000
        provisionService.confirmProvision(CUST, P1);
        ArProvision before = provisionDao.selectConfirmed(P1, CUST);
        BigDecimal targetBefore = before.getTargetBalance();
        // 调比例 91-180 段 0.05 → 0.10
        sysParamService.setValue("AR_BAD_DEBT_RATES", "0.005,0.01,0.02,0.10,0.10,0.30",
                "STRING", "FIN", "junit 调比例", "junit");
        // 已落库台账不变（快照留痕）
        ArProvision after = provisionDao.selectConfirmed(P1, CUST);
        assertEquals(0, targetBefore.compareTo(after.getTargetBalance()), "已确认台账不回溯");
        // 新期试算按新比例
        Map<String, Object> t2 = provisionService.trial(CUST, P2);
        // P2 时点该卡逾期 122 天 → 91-180，rate 0.10 → 2000
        assertEquals(0, new BigDecimal("2000.00").compareTo((BigDecimal) t2.get("target")),
                "新期试算按新比例");
    }

    @Test
    void checklistFifthItemBlocksClose() {
        // 造一条已确认但凭证未过账（指向不存在凭证 → 计为未过账）的台账
        jdbc.update("INSERT INTO erp_fin_ar_provision "
                + "(ID,PROVISION_NO,PERIOD,CUSTOMER_ID,CUSTOMER_NAME,TARGET_BALANCE,BOOKED_BALANCE,"
                + "PERIOD_ACCRUAL,REVERSED_FLAG,VOUCHER_ID,STATUS,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES (?,?,?,?,?,1000,0,1000,'0',?,'CONFIRMED','junit','0',0)",
                "agt-ghost", "PVD209909-AGT", "209909", CUST, "AGT 客户", "ghost-unposted-voucher");
        Map<String, Object> cl = periodService.checklist("209909");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) cl.get("items");
        Map<String, Object> fifth = items.stream()
                .filter(i -> "pendingProvision".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, fifth.get("ok"), "未过账计提阻断结账");
        assertTrue(String.valueOf(fifth.get("detail")).contains("1"), "列示未过账条数");
    }
}
