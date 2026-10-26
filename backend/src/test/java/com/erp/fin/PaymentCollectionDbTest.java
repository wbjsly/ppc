package com.erp.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.BankReceiptDao;
import com.erp.dao.fin.CashDocDao;
import com.erp.dao.fin.CashWriteoffDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.BankReceipt;
import com.erp.entity.fin.CashDoc;
import com.erp.entity.fin.CashWriteoff;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.fin.BankReceiptService;
import com.erp.service.fin.CashDocService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-payment-collection：统一收付款中心 DbTest（spec payment-collection +
 * bank-receipt-reconciliation + bank-account ADDED）。
 * 覆盖：IN 全额/部分/转预收凭证、OUT 应付/预付、余额双向、币种断言 C-4.6-11、
 * 跨期审批 C-4.6-02 四场景、回单六场景、信用统计读路径回归（cash_writeoff 承接）。
 * 隔离：极端未来日期 2099-03 + PC-/PCR- 前缀 + purge 自洽。
 */
@SpringBootTest
class PaymentCollectionDbTest {

    @Autowired
    private CashDocService cashDocService;
    @Autowired
    private BankReceiptService bankReceiptService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private CashDocDao docDao;
    @Autowired
    private CashWriteoffDao woDao;
    @Autowired
    private BankReceiptDao receiptDao;
    @Autowired
    private ArInvoiceDao arDao;
    @Autowired
    private FinApInvoiceDao apDao;
    @Autowired
    private FinBankAccountDao bankDao;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String CUST = "PC-CUST-001";
    private static final String SUP = "PC-SUP-001";
    /** 同期基准：单据与发票同落 209903 */
    private static final LocalDate SAME = LocalDate.of(2099, 3, 10);
    /** 跨期基准：发票 209902 / 单据 209903 */
    private static final LocalDate CROSS_AR = LocalDate.of(2099, 2, 10);

    private String accountId;

    @BeforeEach
    void setUp() {
        purge();
        login();
        FinBankAccount a = new FinBankAccount();
        a.setId("bank-pc-junit");
        a.setAccountName("PC 测试户");
        a.setAccountNo("PCBANK" + (System.nanoTime() % 100000000));
        a.setCurrency("CNY");
        a.setBalance(new BigDecimal("1000000.00"));
        // add-fx-revaluation 三列模型：CNY 账户 FC_BALANCE≡BALANCE、BOOKING_RATE≡1（守卫查 FC 列）
        a.setFcBalance(new BigDecimal("1000000.00"));
        a.setBookingRate(BigDecimal.ONE);
        a.setStatus(FinBankAccount.ST_ACTIVE);
        bankDao.insert(a);
        accountId = a.getId();
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
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_DOC_NO IN (SELECT DOC_NO FROM erp_fin_cash_doc WHERE PARTY_ID LIKE 'PC-%')");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_DOC_NO IN "
                + "(SELECT DOC_NO FROM erp_fin_cash_doc WHERE PARTY_ID LIKE 'PC-%')");
        jdbc.update("DELETE FROM erp_fin_cash_writeoff WHERE DOC_ID IN "
                + "(SELECT ID FROM erp_fin_cash_doc WHERE PARTY_ID LIKE 'PC-%')");
        jdbc.update("DELETE FROM erp_fin_cash_writeoff WHERE TARGET_NO LIKE 'AR-PC-%' "
                + "OR TARGET_NO LIKE 'INV-PC-%'");
        jdbc.update("DELETE FROM erp_fin_cash_doc WHERE PARTY_ID LIKE 'PC-%'");
        jdbc.update("DELETE FROM erp_fin_bank_receipt WHERE RECEIPT_NO LIKE 'PCR-%' "
                + "OR RECEIPT_NO LIKE 'SMKPC-%'"); // 冒烟段 20 残留防 autoMatch 全局扫描误配
        jdbc.update("DELETE FROM erp_fin_ar_invoice WHERE CUSTOMER_ID LIKE 'PC-%'");
        jdbc.update("DELETE FROM erp_fin_ap_invoice WHERE SUPPLIER_ID LIKE 'PC-%'");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE='CashWriteoffCross' AND i.BIZ_ID IN "
                + "(SELECT ID FROM erp_fin_cash_doc WHERE PARTY_ID LIKE 'PC-%')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='CashWriteoffCross' "
                + "AND BIZ_ID IN (SELECT ID FROM erp_fin_cash_doc WHERE PARTY_ID LIKE 'PC-%')");
        jdbc.update("DELETE FROM erp_fin_bank_account WHERE ID=?", "bank-pc-junit");
    }

    // ---------- 数据准备 ----------

    private ArInvoice ar(String no, BigDecimal amt, BigDecimal paid, LocalDate invoiceDate) {
        ArInvoice ar = new ArInvoice();
        ar.setId("AR-PC-" + no);
        ar.setArNo("AR-PC-" + no);
        ar.setCustomerId(CUST);
        ar.setCustomerCode("PCC");
        ar.setCustomerName("PC 客户");
        ar.setAmount(amt);
        ar.setPaidAmount(paid);
        ar.setRedAmount(BigDecimal.ZERO);
        ar.setInvoiceDate(invoiceDate);
        ar.setDueDate(invoiceDate.plusDays(30));
        ar.setStatus(paid.signum() > 0 ? "PARTIAL" : "UNPAID");
        arDao.insert(ar);
        return ar;
    }

    private FinApInvoice ap(String no, BigDecimal amt, LocalDate invoiceDate) {
        FinApInvoice inv = new FinApInvoice();
        inv.setId("INV-PC-" + no);
        inv.setInvoiceNo("INV-PC-" + no);
        inv.setSupplierId(SUP);
        inv.setSupplierName("PC 供应商");
        inv.setInvoiceDate(invoiceDate);
        inv.setCurrency("CNY");
        inv.setTotalAmount(amt);
        inv.setPaidAmount(BigDecimal.ZERO);
        inv.setStatus(FinApInvoice.ST_POSTED);
        apDao.insert(inv);
        return inv;
    }

    private Map<String, Object> inPayload(String amount, String payDate, String currency) {
        Map<String, Object> p = new HashMap<>();
        p.put("direction", "IN");
        p.put("partyId", CUST);
        p.put("partyName", "PC 客户");
        p.put("amount", amount);
        p.put("payMethod", "电汇");
        p.put("bankAccountId", accountId);
        p.put("payDate", payDate);
        p.put("currency", currency);
        return p;
    }

    private Map<String, Object> outPayload(String amount) {
        Map<String, Object> p = new HashMap<>();
        p.put("direction", "OUT");
        p.put("partyId", SUP);
        p.put("partyName", "PC 供应商");
        p.put("amount", amount);
        p.put("payMethod", "电汇");
        p.put("bankAccountId", accountId);
        p.put("payDate", SAME.toString());
        p.put("currency", "CNY");
        return p;
    }

    private BigDecimal balance() {
        return bankDao.selectById(accountId).getBalance();
    }

    private List<Map<String, Object>> voucherLines(CashDoc doc) {
        return jdbc.queryForList("SELECT l.ACCOUNT_CODE, l.DIRECTION, l.AMOUNT "
                + "FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_DOC_NO=? ORDER BY l.LINE_NO", doc.getDocNo());
    }

    // ---------- IN 方向 ----------

    @Test
    void inFullSettlePostsSingleFundsVoucher() {
        ar("A1", new BigDecimal("8000"), BigDecimal.ZERO, LocalDate.of(2099, 3, 1));
        CashDoc doc = cashDocService.create(inPayload("8000", SAME.toString(), "CNY"));
        assertEquals(CashDoc.ST_PENDING_POST, doc.getStatus());

        Map<String, Object> out = cashDocService.post(doc.getId());
        assertEquals(CashDoc.ST_POSTED, out.get("status"));
        assertEquals(0, new BigDecimal("8000").compareTo((BigDecimal) out.get("writeoffTotal")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("remaining")));
        assertEquals(false, out.get("crossPeriod"));

        // 单张资金凭证：借 1002 / 贷 1122（spec 过账资金凭证）
        List<Map<String, Object>> lines = voucherLines(docDao.selectById(doc.getId()));
        assertEquals(2, lines.size());
        assertEquals("1002", lines.get(0).get("ACCOUNT_CODE"));
        assertEquals("1122", lines.get(1).get("ACCOUNT_CODE"));

        // 余额入账 + 发票已清 + 核销行落统一表
        assertEquals(0, new BigDecimal("1008000.00").compareTo(balance()));
        assertEquals(0, new BigDecimal("8000").compareTo(arDao.selectById("AR-PC-A1").getPaidAmount()));
        assertEquals(1, woDao.selectCount(new LambdaQueryWrapper<CashWriteoff>()
                .eq(CashWriteoff::getTargetType, "AR")
                .eq(CashWriteoff::getCustomerId, CUST)));
        // 同期免审批：无 CashWriteoffCross 审批单
        assertNull(docDao.selectById(doc.getId()).getApprId());
    }

    @Test
    void inPartialSettleCreditsPreReceiptForRemainder() {
        ar("B1", new BigDecimal("5000"), BigDecimal.ZERO, LocalDate.of(2099, 3, 2));
        CashDoc doc = cashDocService.create(inPayload("10000", SAME.toString(), "CNY"));
        Map<String, Object> out = cashDocService.post(doc.getId());
        assertEquals(0, new BigDecimal("5000").compareTo((BigDecimal) out.get("writeoffTotal")));
        assertEquals(0, new BigDecimal("5000").compareTo((BigDecimal) out.get("remaining")));

        // 凭证三行：借 1002 10000 / 贷 1122 5000 / 贷 2205 5000（预收 = 差额）
        List<Map<String, Object>> lines = voucherLines(docDao.selectById(doc.getId()));
        assertEquals(3, lines.size());
        assertEquals("2205", lines.get(2).get("ACCOUNT_CODE"));
        assertEquals(0, new BigDecimal("5000")
                .compareTo(new BigDecimal(String.valueOf(lines.get(2).get("AMOUNT")))));
    }

    @Test
    void inWithoutOpenArCreditsFullPreReceipt() {
        CashDoc doc = cashDocService.create(inPayload("3000", SAME.toString(), "CNY"));
        Map<String, Object> out = cashDocService.post(doc.getId());
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("writeoffTotal")));
        assertEquals(0, new BigDecimal("3000").compareTo((BigDecimal) out.get("remaining")));
        List<Map<String, Object>> lines = voucherLines(docDao.selectById(doc.getId()));
        assertEquals(2, lines.size());
        assertEquals("2205", lines.get(1).get("ACCOUNT_CODE"));
    }

    // ---------- 外币单据（add-fx-revaluation task 3.2）----------

    @Test
    void usdInMaintainsDualColumnsAndVouchersInCny() {
        // USD 账户（记账汇率 7.12）：期初 5000 USD = 35600.00 CNY
        FinBankAccount usd = new FinBankAccount();
        usd.setId("bank-pc-usd");
        usd.setAccountName("PC USD 户");
        usd.setAccountNo("PCUSD" + (System.nanoTime() % 100000000));
        usd.setCurrency("USD");
        usd.setBalance(new BigDecimal("35600.00"));
        usd.setFcBalance(new BigDecimal("5000"));
        usd.setBookingRate(new BigDecimal("7.120000"));
        usd.setStatus(FinBankAccount.ST_ACTIVE);
        bankDao.insert(usd);
        try {
            Map<String, Object> p = inPayload("1000", SAME.toString(), "USD");
            p.put("bankAccountId", "bank-pc-usd");
            // 客户无未清应收 → 全额转预收；币种 USD=USD 通过账户维度断言
            CashDoc doc = cashDocService.create(p);
            cashDocService.post(doc.getId());

            // 双列维护：FC_BALANCE +1000，BALANCE +7120.00（1000×7.12）
            FinBankAccount after = bankDao.selectById("bank-pc-usd");
            assertEquals(0, new BigDecimal("6000").compareTo(after.getFcBalance()),
                    "FC_BALANCE 维护外币额");
            assertEquals(0, new BigDecimal("42720.00").compareTo(after.getBalance()),
                    "BALANCE 维护本位币额（外币额×记账汇率）");

            // 凭证本位币口径：借 1002 7120.00 / 贷 2205 7120.00
            List<Map<String, Object>> lines = voucherLines(docDao.selectById(doc.getId()));
            assertEquals(2, lines.size());
            assertEquals("1002", lines.get(0).get("ACCOUNT_CODE"));
            assertEquals(0, new BigDecimal("7120.00")
                    .compareTo(new BigDecimal(String.valueOf(lines.get(0).get("AMOUNT")))),
                    "银行存款行按本位币记账");
            assertEquals("2205", lines.get(1).get("ACCOUNT_CODE"));
            assertEquals(0, new BigDecimal("7120.00")
                    .compareTo(new BigDecimal(String.valueOf(lines.get(1).get("AMOUNT")))),
                    "预收账款差额行吸收折算后金额");
        } finally {
            jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                    + "WHERE v.SOURCE_DOC_NO IN (SELECT DOC_NO FROM erp_fin_cash_doc WHERE ID='bank-pc-usd' "
                    + "OR BANK_ACCOUNT_ID='bank-pc-usd')");
            jdbc.update("DELETE FROM erp_fin_voucher WHERE ID IN "
                    + "(SELECT VOUCHER_ID FROM erp_fin_cash_doc WHERE BANK_ACCOUNT_ID='bank-pc-usd')");
            jdbc.update("DELETE FROM erp_fin_cash_doc WHERE BANK_ACCOUNT_ID='bank-pc-usd'");
            jdbc.update("DELETE FROM erp_fin_bank_account WHERE ID='bank-pc-usd'");
        }
    }

    @Test
    void usdDocOnCnyAccountBlocksOnCurrency() {
        // USD 单据挂 CNY 账户 → 账户维度币种断言阻断（防外币额误维护本位币余额）
        CashDoc doc = cashDocService.create(inPayload("1000", SAME.toString(), "USD"));
        ServiceException e = assertThrows(ServiceException.class, () -> cashDocService.post(doc.getId()));
        assertTrue(e.getMessage().contains("不一致"), e.getMessage());
        assertEquals(0, new BigDecimal("1000000.00").compareTo(balance()), "余额未动");
    }

    // ---------- OUT 方向 ----------

    @Test
    void outPaymentSettlesApAndDeductsBalance() {
        ap("P1", new BigDecimal("60000"), LocalDate.of(2099, 3, 1));
        BigDecimal before = balance();
        CashDoc doc = cashDocService.create(outPayload("60000"));
        Map<String, Object> out = cashDocService.post(doc.getId());
        assertEquals(0, new BigDecimal("60000").compareTo((BigDecimal) out.get("writeoffTotal")));
        // 凭证：借 2202 / 贷 1002；余额条件扣减；发票 PAID
        List<Map<String, Object>> lines = voucherLines(docDao.selectById(doc.getId()));
        assertEquals(2, lines.size());
        assertEquals("2202", lines.get(0).get("ACCOUNT_CODE"));
        assertEquals(0, before.subtract(new BigDecimal("60000")).compareTo(balance()));
        assertEquals(0, new BigDecimal("60000")
                .compareTo(apDao.selectById("INV-PC-P1").getPaidAmount()));
    }

    @Test
    void outPrepaySkipsSettleAndDebitsPrepayAccount() {
        Map<String, Object> p = outPayload("20000");
        CashDoc doc = cashDocService.create(p);
        doc.setPayType(CashDoc.TYPE_PREPAY);
        docDao.updateById(doc);
        Map<String, Object> out = cashDocService.post(doc.getId());
        // 预付不核销发票：核销合计 0，凭证首行借 1123
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("writeoffTotal")));
        assertEquals(0, woDao.selectCount(new LambdaQueryWrapper<CashWriteoff>()
                .eq(CashWriteoff::getDocId, doc.getId())));
        List<Map<String, Object>> lines = voucherLines(docDao.selectById(doc.getId()));
        assertEquals("1123", lines.get(0).get("ACCOUNT_CODE"));
    }

    // ---------- 币种断言（C-4.6-11） ----------

    @Test
    void currencyMismatchBlocksSettlement() {
        ap("C1", new BigDecimal("10000"), LocalDate.of(2099, 3, 3));
        CashDoc doc = cashDocService.create(outPayload("10000"));
        doc.setCurrency("USD");
        docDao.updateById(doc);
        ServiceException e = assertThrows(ServiceException.class, () -> cashDocService.post(doc.getId()));
        assertTrue(e.getMessage().contains("币种"), e.getMessage());
        // 全回滚：余额不变、发票未清、单据未过账
        assertEquals(0, new BigDecimal("1000000.00").compareTo(balance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(apDao.selectById("INV-PC-C1").getPaidAmount()));
        assertEquals(CashDoc.ST_PENDING_POST, docDao.selectById(doc.getId()).getStatus());
    }

    // ---------- 跨期核销审批（C-4.6-02） ----------

    @Test
    void crossPeriodSettlePendsForApproval() {
        ar("X1", new BigDecimal("4000"), BigDecimal.ZERO, CROSS_AR);
        CashDoc doc = cashDocService.create(inPayload("4000", SAME.toString(), "CNY"));
        Map<String, Object> out = cashDocService.post(doc.getId());
        assertEquals(true, out.get("crossPeriod"));

        CashDoc after = docDao.selectById(doc.getId());
        assertEquals(CashDoc.WO_PENDING, after.getWriteoffState());
        assertEquals("1", after.getCrossPeriod());
        assertNotNull(after.getApprId());
        // 资金已过账但核销挂起：发票 PAID 不变、核销行已落库
        assertEquals(0, BigDecimal.ZERO.compareTo(arDao.selectById("AR-PC-X1").getPaidAmount()));
        assertEquals(1, woDao.selectCount(new LambdaQueryWrapper<CashWriteoff>()
                .eq(CashWriteoff::getDocId, doc.getId())));
        assertEquals(0, new BigDecimal("1004000.00").compareTo(balance()));
    }

    @Test
    void crossPeriodApprovalAppliesWriteoff() {
        ar("X2", new BigDecimal("4000"), BigDecimal.ZERO, CROSS_AR);
        CashDoc doc = cashDocService.create(inPayload("4000", SAME.toString(), "CNY"));
        cashDocService.post(doc.getId());

        String taskId = jdbc.queryForObject("SELECT t.ID FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE' LIMIT 1", String.class, doc.getId());
        loginAsFinanceMgr();
        approvalEngine.pass(taskId, "跨期核销同意");

        assertEquals(CashDoc.WO_DONE, docDao.selectById(doc.getId()).getWriteoffState());
        assertEquals(0, new BigDecimal("4000").compareTo(arDao.selectById("AR-PC-X2").getPaidAmount()));
        assertEquals("PAID", arDao.selectById("AR-PC-X2").getStatus());
    }

    @Test
    void crossPeriodRejectionCancelsWriteoff() {
        ar("X3", new BigDecimal("4000"), BigDecimal.ZERO, CROSS_AR);
        CashDoc doc = cashDocService.create(inPayload("4000", SAME.toString(), "CNY"));
        cashDocService.post(doc.getId());

        String taskId = jdbc.queryForObject("SELECT t.ID FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE' LIMIT 1", String.class, doc.getId());
        loginAsFinanceMgr();
        approvalEngine.reject(taskId, "期间口径有误驳回");

        CashDoc after = docDao.selectById(doc.getId());
        assertEquals(CashDoc.WO_REJECTED, after.getWriteoffState());
        // 核销取消：未清额不变、挂起核销行删除
        assertEquals(0, BigDecimal.ZERO.compareTo(arDao.selectById("AR-PC-X3").getPaidAmount()));
        assertEquals(0, woDao.selectCount(new LambdaQueryWrapper<CashWriteoff>()
                .eq(CashWriteoff::getDocId, doc.getId())));
        // 资金凭证保留（已过账不回滚）
        assertEquals(0, new BigDecimal("1004000.00").compareTo(balance()));
    }

    private void loginAsFinanceMgr() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_FINANCE_MGR"));
    }

    // ---------- 回单对账（bank-receipt-reconciliation） ----------

    private CashDoc postedIn(String amount) {
        CashDoc doc = cashDocService.create(inPayload(amount, SAME.toString(), "CNY"));
        cashDocService.post(doc.getId());
        return docDao.selectById(doc.getId());
    }

    private Map<String, Object> receiptPayload(String no, String amount) {
        Map<String, Object> p = new HashMap<>();
        p.put("receiptNo", no);
        p.put("bankAccountId", accountId);
        p.put("tradeDate", SAME.toString());
        p.put("direction", "RECEIVE");
        p.put("amount", amount);
        p.put("counterparty", "PC 客户");
        return p;
    }

    @Test
    void receiptAutoMatchReconcilesDoc() {
        CashDoc doc = postedIn("5000");
        bankReceiptService.create(receiptPayload("PCR-001", "5000"));
        bankReceiptService.autoMatch(); // 全局匹配（共享库可能含他源对），断言聚焦本回单终态

        BankReceipt r = receiptDao.selectList(new LambdaQueryWrapper<BankReceipt>()
                .eq(BankReceipt::getReceiptNo, "PCR-001")).get(0);
        assertEquals(BankReceipt.ST_MATCHED, r.getMatchStatus());
        assertEquals(doc.getDocNo(), r.getMatchedDocNo());
        assertEquals(CashDoc.ST_RECONCILED, docDao.selectById(doc.getId()).getStatus());
    }

    @Test
    void manualMatchRejectsDirectionMismatch() {
        postedIn("5000");
        BankReceipt r = bankReceiptService.create(receiptPayload("PCR-002", "5000"));
        r.setDirection("PAY");
        receiptDao.updateById(r);
        CashDoc doc = docDao.selectList(new LambdaQueryWrapper<CashDoc>()
                .eq(CashDoc::getPartyId, CUST)).get(0);
        ServiceException e = assertThrows(ServiceException.class,
                () -> bankReceiptService.manualMatch(r.getId(), doc.getId()));
        assertTrue(e.getMessage().contains("方向"), e.getMessage());
    }

    @Test
    void duplicateMatchRejected() {
        CashDoc doc = postedIn("5000");
        BankReceipt r = bankReceiptService.create(receiptPayload("PCR-003", "5000"));
        bankReceiptService.manualMatch(r.getId(), doc.getId());
        CashDoc doc2 = postedIn("5000");
        ServiceException e = assertThrows(ServiceException.class,
                () -> bankReceiptService.manualMatch(r.getId(), doc2.getId()));
        assertTrue(e.getMessage().contains("已匹配") || e.getMessage().contains("重复"), e.getMessage());
    }

    @Test
    void markExceptionAndUnreconcileRoundtrip() {
        // 异常：无候选单据的回单可标记 EXCEPTION
        BankReceipt r = bankReceiptService.create(receiptPayload("PCR-004", "777"));
        BankReceipt ex = bankReceiptService.markException(r.getId(), "银行手续费无对应单据");
        assertEquals(BankReceipt.ST_EXCEPTION, ex.getMatchStatus());

        // 解除匹配：MATCHED → UNMATCHED + 单据回 POSTED，留痕
        CashDoc doc = postedIn("5000");
        BankReceipt r2 = bankReceiptService.create(receiptPayload("PCR-005", "5000"));
        bankReceiptService.manualMatch(r2.getId(), doc.getId());
        BankReceipt un = bankReceiptService.unreconcile(r2.getId(), "误匹配");
        assertEquals(BankReceipt.ST_UNMATCHED, un.getMatchStatus());
        assertNotNull(un.getReconcileReason());
        assertEquals(CashDoc.ST_POSTED, docDao.selectById(doc.getId()).getStatus());
    }

    @Test
    void reconciledDocBlocksVoid() {
        CashDoc doc = postedIn("5000");
        BankReceipt r = bankReceiptService.create(receiptPayload("PCR-006", "5000"));
        bankReceiptService.manualMatch(r.getId(), doc.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> cashDocService.voidDoc(doc.getId(), "测试作废"));
        assertTrue(e.getMessage().contains("作废"), e.getMessage());
    }

    // ---------- 信用统计读路径回归（cash_writeoff 承接旧 ArWriteoff 口径） ----------

    @Test
    void creditStatsReadFromUnifiedWriteoff() {
        ar("S1", new BigDecimal("2000"), BigDecimal.ZERO, LocalDate.of(2099, 3, 4));
        CashDoc doc = cashDocService.create(inPayload("2000", SAME.toString(), "CNY"));
        cashDocService.post(doc.getId());

        // 近 12 个月回款笔数（CreditControl 口径，CUSTOMER_ID 列承接）
        assertEquals(1, woDao.selectCount12m(CUST));
        assertEquals(1, woDao.selectOnTimeCount12m(CUST)); // 03-10 收款 ≤ 04-03 到期
        // 某应收累计核销（对账/CRM 口径）
        assertEquals(0, new BigDecimal("2000")
                .compareTo(woDao.selectSumByAr("AR-PC-S1")));
    }
}
