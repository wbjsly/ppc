package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.service.fin.FxRevalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-fx-revaluation task 4.2：重估试算内核（spec fx-revaluation，FR-4.6-4-1~4）。
 * 三类项目覆盖 / 期末汇率缺失阻断 / 无敞口空返回 / IAS 21 精度。
 */
@SpringBootTest
class FxRevalTrialDbTest {

    @Autowired
    private FxRevalService revalService;
    @Autowired
    private FinApInvoiceDao apDao;
    @Autowired
    private ArInvoiceDao arDao;
    @Autowired
    private FinBankAccountDao bankDao;
    @Autowired
    private JdbcTemplate jdbc;

    /** 试算期间 209911（期末 2099-11-30），未来隔离 */
    private static final String PERIOD = "209911";

    @BeforeEach
    void setUp() {
        purge();
        // 期末中间价：USD 7.20 / EUR 7.80（2099-11 全月生效）
        jdbc.update("INSERT IGNORE INTO erp_mdm_exchange_rate (ID,BASE_CCY,QUOTE_CCY,RATE_TYPE,"
                + "EFFECTIVE_DATE,EXPIRE_DATE,RATE,SOURCE_FILE_NO,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('fx-rvt-usd','USD','CNY','MIDDLE','2099-11-01','2099-11-30',7.200000,'央行','junit','0',0),"
                + "('fx-rvt-eur','EUR','CNY','MIDDLE','2099-11-01','2099-11-30',7.800000,'央行','junit','0',0)");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void purge() {
        // 冒烟段 21 残留（SMKFX 外币项目会被全表扫描进试算池——共享库隔离）
        jdbc.update("DELETE FROM erp_fin_ap_invoice WHERE INVOICE_NO LIKE 'SMKFX%'");
        jdbc.update("DELETE FROM erp_fin_ar_invoice WHERE AR_NO LIKE 'SMKFX%'");
        jdbc.update("DELETE FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'SMKFX%'");
        jdbc.update("DELETE FROM erp_fin_fx_reval WHERE PERIOD LIKE '2099%'");
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='REVAL'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='REVAL'");
        jdbc.update("DELETE FROM erp_fin_ap_invoice WHERE INVOICE_NO LIKE 'RVT-%'");
        jdbc.update("DELETE FROM erp_fin_ar_invoice WHERE AR_NO LIKE 'RVT-%'");
        jdbc.update("DELETE FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'RVT-%'");
        jdbc.update("DELETE FROM erp_mdm_exchange_rate WHERE ID IN ('fx-rvt-usd','fx-rvt-eur')");
        // 冒烟汇率残留（fx-smkfx-u11 覆盖 209911 与 fx-rvt 重叠，selectHit 会取错价）
        jdbc.update("DELETE FROM erp_mdm_exchange_rate WHERE ID LIKE 'fx-smkfx-%'");
    }

    /** AP 未清：USD 10000 @ 记账 7.12 */
    private void seedAp(String no, String ccy, String total, String rate) {
        FinApInvoice inv = new FinApInvoice();
        inv.setId("RVT-AP-" + no);
        inv.setInvoiceNo("RVT-" + no);
        inv.setSupplierId("SUP-RVT");
        inv.setSupplierName("RVT 供应商");
        inv.setInvoiceDate(java.time.LocalDate.of(2099, 10, 1));
        inv.setCurrency(ccy);
        inv.setBookingRate(new BigDecimal(rate));
        inv.setTotalAmount(new BigDecimal(total));
        inv.setPaidAmount(BigDecimal.ZERO);
        inv.setStatus(FinApInvoice.ST_POSTED);
        apDao.insert(inv);
    }

    /** AR 未清：USD 5000 @ 记账 7.10 */
    private void seedAr(String no, String ccy, String amount, String rate) {
        ArInvoice ar = new ArInvoice();
        ar.setId("RVT-AR-" + no);
        ar.setArNo("RVT-" + no);
        ar.setCustomerId("CUST-RVT");
        ar.setCustomerCode("RVT");
        ar.setCustomerName("RVT 客户");
        ar.setCurrency(ccy);
        ar.setBookingRate(new BigDecimal(rate));
        ar.setAmount(new BigDecimal(amount));
        ar.setPaidAmount(BigDecimal.ZERO);
        ar.setRedAmount(BigDecimal.ZERO);
        ar.setInvoiceDate(java.time.LocalDate.of(2099, 10, 5));
        ar.setDueDate(java.time.LocalDate.of(2099, 11, 4));
        ar.setStatus("UNPAID");
        arDao.insert(ar);
    }

    /** 外币账户：USD 8000 @ 开户 7.15 */
    private void seedBank(String no, String ccy, String fc, String rate) {
        FinBankAccount a = new FinBankAccount();
        a.setId("RVT-BK-" + no);
        a.setAccountName("RVT " + ccy + " 户");
        a.setAccountNo("RVT-" + no);
        a.setCurrency(ccy);
        a.setFcBalance(new BigDecimal(fc));
        a.setBookingRate(new BigDecimal(rate));
        a.setBalance(new BigDecimal(fc).multiply(new BigDecimal(rate))
                .setScale(2, java.math.RoundingMode.HALF_UP));
        a.setStatus(FinBankAccount.ST_ACTIVE);
        bankDao.insert(a);
    }

    // ---------- 1. 三类项目覆盖 + IAS 21 精度 ----------

    @Test
    void trialCoversThreeItemTypesWithIas21Precision() {
        seedAp("AP1", "USD", "10000", "7.120000");
        seedAr("AR1", "USD", "5000", "7.100000");
        seedBank("BK1", "USD", "8000", "7.150000");

        Map<String, Object> out = revalService.trialRun(PERIOD);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals(3, items.size(), "AP/AR/银行三类各一笔");

        // 差额 = fc ×（7.20 − 记账汇率）
        // AP: 10000×(7.20−7.12)=800.00；AR: 5000×(7.20−7.10)=500.00；BANK: 8000×(7.20−7.15)=400.00
        Map<String, BigDecimal> byNo = new java.util.HashMap<>();
        for (Map<String, Object> it : items) {
            byNo.put((String) it.get("itemNo"), (BigDecimal) it.get("diffAmount"));
        }
        assertEquals(0, new BigDecimal("800.00").compareTo(byNo.get("RVT-AP1")), "AP 汇兑差额");
        assertEquals(0, new BigDecimal("500.00").compareTo(byNo.get("RVT-AR1")), "AR 汇兑差额");
        assertEquals(0, new BigDecimal("400.00").compareTo(byNo.get("RVT-BK1")), "银行汇兑差额");

        // 总额与汇总
        assertEquals(0, new BigDecimal("1700.00").compareTo((BigDecimal) out.get("totalDiff")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) out.get("summary");
        assertEquals(3, summary.size(), "币种×类型三行汇总（USD-AP/USD-AR/USD-BANK）");
        // 试算不落库（design D6）
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_fx_reval WHERE PERIOD=?", Integer.class, PERIOD));
    }

    // ---------- 2. 期末汇率缺失阻断（C-4.6-03 逐币种列示）----------

    @Test
    void missingPeriodRateBlocksWithCurrencyList() {
        seedAp("AP2", "JPY", "100000", "0.050000"); // 无 JPY 期末价
        ServiceException ex = assertThrows(ServiceException.class,
                () -> revalService.trialRun(PERIOD));
        assertTrue(ex.getMessage().contains("JPY"), "逐币种列示：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("期末汇率缺失"), "阻断语义：" + ex.getMessage());
    }

    // ---------- 3. 无敞口空返回 ----------

    @Test
    void noExposureReturnsEmpty() {
        // 全 CNY 环境（无外币项目）→ 空明细不抛错
        Map<String, Object> out = revalService.trialRun(PERIOD);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertTrue(items.isEmpty(), "无敞口返回空明细");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("totalDiff")));
    }

    // ---------- 4. 已清发票不纳入 + CNY 账户不纳入 ----------

    @Test
    void clearedInvoicesAndCnyAccountsExcluded() {
        seedAp("AP3", "USD", "10000", "7.120000");
        // 已全额核销 → 不纳入
        jdbc.update("UPDATE erp_fin_ap_invoice SET PAID_AMOUNT=10000 WHERE ID='RVT-AP-AP3'");
        // CNY 账户（FC≠0 但币种 CNY）→ 不纳入
        FinBankAccount cny = new FinBankAccount();
        cny.setId("RVT-BK-CNY");
        cny.setAccountName("RVT CNY 户");
        cny.setAccountNo("RVT-CNY1");
        cny.setCurrency("CNY");
        cny.setBalance(new BigDecimal("999.00"));
        cny.setFcBalance(new BigDecimal("999.00"));
        cny.setBookingRate(BigDecimal.ONE);
        cny.setStatus(FinBankAccount.ST_ACTIVE);
        bankDao.insert(cny);

        Map<String, Object> out = revalService.trialRun(PERIOD);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertTrue(items.isEmpty(), "已清发票与 CNY 账户均不纳入重估范围");
    }
}
