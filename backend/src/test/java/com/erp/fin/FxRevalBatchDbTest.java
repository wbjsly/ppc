package com.erp.fin;

import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FxRevalDao;
import com.erp.entity.fin.FinApInvoice;
import com.erp.service.fin.FxRevalService;
import com.erp.service.fin.GlVoucherService;
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
 * add-fx-revaluation task 4.4：批次列表与明细下钻（spec fx-revaluation）。
 * 批次净额列示 / 下钻明细 / 凭证反查字段。
 */
@SpringBootTest
class FxRevalBatchDbTest {

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

    private static final String PREV = "209909";
    private static final String CUR = "209910";

    @BeforeEach
    void setUp() {
        purge();
        // 显式制单人登录（防前序测试遗留 SecurityContext 污染 C-4.6-07 断言）
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                        "maker", null, "ROLE_ADMIN"));
        jdbc.update("INSERT IGNORE INTO erp_mdm_exchange_rate (ID,BASE_CCY,QUOTE_CCY,RATE_TYPE,"
                + "EFFECTIVE_DATE,EXPIRE_DATE,RATE,SOURCE_FILE_NO,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('fx-bat-sep','USD','CNY','MIDDLE','2099-09-01','2099-09-30',7.180000,'央行','junit','0',0),"
                + "('fx-bat-oct','USD','CNY','MIDDLE','2099-10-01','2099-10-31',7.200000,'央行','junit','0',0)");
        // 两笔外币 AP（不同记账汇率，验证逐笔与汇总）
        seedAp("B1", "7.100000"); // 10000×(7.18−7.10)=800 @上期；×(7.20−7.10)=1000 @本期
        seedAp("B2", "7.160000"); // 10000×(7.18−7.16)=200 @上期；×(7.20−7.16)=400 @本期
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    /** 换审核人（C-4.6-07 制单≠审核） */
    private void loginAuditor() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                        "auditor", null, "ROLE_FINANCE_MGR"));
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
        jdbc.update("DELETE FROM erp_fin_ap_invoice WHERE INVOICE_NO LIKE 'BAT-%'");
        jdbc.update("DELETE FROM erp_mdm_exchange_rate WHERE ID IN ('fx-bat-sep','fx-bat-oct')");
        jdbc.update("DELETE FROM erp_mdm_exchange_rate WHERE ID LIKE 'fx-smkfx-%'");
    }

    private void seedAp(String no, String rate) {
        FinApInvoice inv = new FinApInvoice();
        inv.setId("BAT-AP-" + no);
        inv.setInvoiceNo("BAT-" + no);
        inv.setSupplierId("SUP-BAT");
        inv.setSupplierName("BAT 供应商");
        inv.setInvoiceDate(LocalDate.of(2099, 8, 1));
        inv.setCurrency("USD");
        inv.setBookingRate(new BigDecimal(rate));
        inv.setTotalAmount(new BigDecimal("10000"));
        inv.setPaidAmount(BigDecimal.ZERO);
        inv.setStatus(FinApInvoice.ST_POSTED);
        apDao.insert(inv);
    }

    // ---------- 1. 批次净额列示（上期已过账、本期生成冲回上期）----------

    @Test
    void batchListShowsRevalReversedNet() {
        // 上期生成 + 审核 + 过账
        Map<String, Object> prev = revalService.generate(PREV);
        loginAuditor();
        voucherService.audit((String) prev.get("voucherId"));
        voucherService.post((String) prev.get("voucherId"));
        // 本期生成 → 冲回上期
        revalService.generate(CUR);

        List<Map<String, Object>> batches = revalService.batches();
        assertTrue(batches.size() >= 2, "两期批次：" + batches.size());
        // 顺序倒序：首行为本期
        Map<String, Object> cur = batches.get(0);
        assertEquals(CUR, cur.get("period"), "期间倒序首行是本期");
        // 本期重估 = 1000+400 = 1400.00
        assertEquals(0, new BigDecimal("1400.00").compareTo((BigDecimal) cur.get("revalTotal")));
        // 本期对上期的冲回影响：上期重估 800+200=1000 → 净额 = 1400 − 1000 = 400.00
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) cur.get("reversedTotal")),
                "冲回额=上期重估额");
        assertEquals(2, cur.get("reversedCount"), "上期两笔均被冲回");
        assertEquals(0, new BigDecimal("400.00").compareTo((BigDecimal) cur.get("netTotal")),
                "净额=本期重估−上期冲回");
        assertNotNull(cur.get("voucherNo"), "本期凭证号可反查");
        // 上期批次：重估 1000、无再冲回、净额 1000
        Map<String, Object> prevBatch = batches.get(1);
        assertEquals(PREV, prevBatch.get("period"));
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) prevBatch.get("revalTotal")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) prevBatch.get("reversedTotal")));
    }

    // ---------- 2. 下钻明细 + 币种×类型汇总 ----------

    @Test
    void batchDetailsShowsItemsAndSummary() {
        revalService.generate(CUR);

        Map<String, Object> d = revalService.batchDetails(CUR);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) d.get("items");
        assertEquals(2, items.size(), "两笔逐笔明细");
        // 每笔含记账/期末汇率与差额
        Map<String, Object> b1 = items.stream()
                .filter(i -> "BAT-B1".equals(i.get("itemNo"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("7.100000").compareTo((BigDecimal) b1.get("bookingRate")));
        assertEquals(0, new BigDecimal("7.200000").compareTo((BigDecimal) b1.get("periodRate")));
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) b1.get("diffAmount")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) d.get("summary");
        assertEquals(1, summary.size(), "USD×AP 一行汇总");
        assertEquals(0, new BigDecimal("1400.00").compareTo((BigDecimal) summary.get(0).get("diffTotal")));
        assertEquals(2, summary.get(0).get("itemCount"));
    }

    // ---------- 3. 凭证链字段（本批凭证 + 冲回凭证）----------

    @Test
    void batchDetailsShowsVoucherChain() {
        Map<String, Object> prev = revalService.generate(PREV);
        loginAuditor();
        voucherService.audit((String) prev.get("voucherId"));
        voucherService.post((String) prev.get("voucherId"));
        revalService.generate(CUR);

        Map<String, Object> d = revalService.batchDetails(CUR);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vouchers = (List<Map<String, Object>>) d.get("vouchers");
        // 本期批：本批重估凭证 1 + 冲回凭证 1
        assertEquals(2, vouchers.size(), "凭证链两条");
        assertTrue(vouchers.stream().anyMatch(v -> "本批重估凭证".equals(v.get("role"))));
        Map<String, Object> rev = vouchers.stream()
                .filter(v -> "冲回凭证".equals(v.get("role"))).findFirst().orElseThrow();
        assertNotNull(rev.get("voucherNo"), "冲回凭证号可反查");
    }
}
