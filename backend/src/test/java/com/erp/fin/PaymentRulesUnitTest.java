package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.FinPaymentDao;
import com.erp.dao.fin.FinPaymentRequestDao;
import com.erp.dao.fin.FinPaymentWriteoffDao;
import com.erp.dao.fin.FinPrepaymentDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.qms.ScarDeductionDao;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.FinPaymentRequest;
import com.erp.entity.fin.FinPrepayment;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.qms.ScarDeduction;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.PaymentService;
import com.erp.service.fin.PrepaymentService;
import com.erp.service.fin.SupplierStatementService;
import com.erp.service.impl.fin.PaymentServiceImpl;
import com.erp.service.impl.fin.PrepaymentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 付款域服务层规则单测（spec payment-execution / prepayment / payment-request）：
 * 超审批金额 L1、非法支付方式、抵扣合计超付款额、预付比例与累计双 L1、申请超未清应付。
 * 数据库侧语义（余额条件扣减、PAID_AMOUNT 超核销、冲抵 min()）见 PaymentDbRulesTest。
 */
class PaymentRulesUnitTest {

    @BeforeEach
    void loginAsAdmin() {
        // 服务层 requireRole 需要 SecurityContext（单测无登录态 → 注入 ADMIN）
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "junit", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private PaymentService newPaymentService(FinPaymentRequestDao reqDao, FinPaymentDao payDao,
                                             FinApInvoiceDao invoiceDao, FinBankAccountDao bankDao,
                                             ScarDeductionDao deductionDao) {
        return new PaymentServiceImpl(reqDao, payDao, mock(FinPaymentWriteoffDao.class),
                invoiceDao, bankDao, deductionDao, mock(GlVoucherService.class),
                mock(SupplierStatementService.class), mock(ApprovalEngine.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private PrepaymentService newPrepaymentService(FinPrepaymentDao ppDao, PurchaseOrderDao poDao,
                                                   GoodsReceiptDao grDao, GoodsReceiptLineDao grLineDao,
                                                   MdmSupplierDao supplierDao) {
        PrepaymentServiceImpl s = new PrepaymentServiceImpl(ppDao, mock(FinPaymentDao.class),
                mock(FinPaymentWriteoffDao.class), mock(FinApInvoiceDao.class),
                mock(FinBankAccountDao.class), poDao, grDao, grLineDao, supplierDao,
                mock(GlVoucherService.class), mock(SupplierStatementService.class),
                mock(ApprovalEngine.class), new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(s, "defaultPrepayRatio", new BigDecimal("0.30"));
        return s;
    }

    private FinPaymentRequest scheduledRequest(String amount) {
        FinPaymentRequest r = new FinPaymentRequest();
        r.setId("req-junit");
        r.setReqNo("PA20261006001");
        r.setSupplierId("sup-junit");
        r.setApplyAmount(new BigDecimal(amount));
        r.setStatus(FinPaymentRequest.ST_SCHEDULED);
        r.setInvoiceIds("[\"inv-junit\"]");
        return r;
    }

    private Map<String, Object> payload(String amount, String method, String deductId) {
        Map<String, Object> p = new HashMap<>();
        p.put("reqId", "req-junit");
        p.put("applyAmount", amount);
        p.put("payMethod", method);
        p.put("bankAccountId", "bank-junit");
        p.put("payDate", LocalDate.now().toString());
        List<String> ids = new ArrayList<>();
        if (deductId != null) {
            ids.add(deductId);
        }
        p.put("deductIds", ids);
        return p;
    }

    private FinBankAccount activeBank() {
        FinBankAccount a = new FinBankAccount();
        a.setId("bank-junit");
        a.setAccountName("JUnit 账户");
        a.setAccountNo("JUNIT001");
        a.setStatus(FinBankAccount.ST_ACTIVE);
        a.setBalance(new BigDecimal("1000000.00"));
        return a;
    }

    // ---------------- payment-execution ----------------

    @Test
    void executeRejectsOverApprovedAmount() {
        FinPaymentRequestDao reqDao = mock(FinPaymentRequestDao.class);
        FinPaymentDao payDao = mock(FinPaymentDao.class);
        when(reqDao.selectById("req-junit")).thenReturn(scheduledRequest("1000"));
        when(payDao.selectExecutedByReq(anyString())).thenReturn(BigDecimal.ZERO);
        PaymentService svc = newPaymentService(reqDao, payDao, mock(FinApInvoiceDao.class),
                mock(FinBankAccountDao.class), mock(ScarDeductionDao.class));

        ServiceException e = assertThrows(ServiceException.class, () ->
                svc.execute(payload("2000", "电汇", null)));
        assertTrue(e.getMessage().contains("超过审批金额"), e.getMessage());
    }

    @Test
    void executeRejectsUnknownPayMethod() {
        FinPaymentRequestDao reqDao = mock(FinPaymentRequestDao.class);
        FinPaymentDao payDao = mock(FinPaymentDao.class);
        when(reqDao.selectById("req-junit")).thenReturn(scheduledRequest("1000"));
        when(payDao.selectExecutedByReq(anyString())).thenReturn(BigDecimal.ZERO);
        PaymentService svc = newPaymentService(reqDao, payDao, mock(FinApInvoiceDao.class),
                mock(FinBankAccountDao.class), mock(ScarDeductionDao.class));

        ServiceException e = assertThrows(ServiceException.class, () ->
                svc.execute(payload("500", "比特币", null)));
        assertTrue(e.getMessage().contains("支付方式"), e.getMessage());
    }

    @Test
    void executeRejectsDeductExceedingAmount() {
        FinPaymentRequestDao reqDao = mock(FinPaymentRequestDao.class);
        FinPaymentDao payDao = mock(FinPaymentDao.class);
        FinBankAccountDao bankDao = mock(FinBankAccountDao.class);
        ScarDeductionDao deductionDao = mock(ScarDeductionDao.class);
        when(reqDao.selectById("req-junit")).thenReturn(scheduledRequest("3000"));
        when(payDao.selectExecutedByReq(anyString())).thenReturn(BigDecimal.ZERO);
        when(bankDao.selectById("bank-junit")).thenReturn(activeBank());
        ScarDeduction d = new ScarDeduction();
        d.setId("sd-junit");
        d.setDeductNo("SD-JUNIT");
        d.setSupplierId("sup-junit");
        d.setAmount(new BigDecimal("5000.00"));
        d.setStatus("TO_DEDUCT");
        when(deductionDao.selectById("sd-junit")).thenReturn(d);
        PaymentService svc = newPaymentService(reqDao, payDao, mock(FinApInvoiceDao.class),
                bankDao, deductionDao);

        ServiceException e = assertThrows(ServiceException.class, () ->
                svc.execute(payload("3000", "电汇", "sd-junit")));
        assertTrue(e.getMessage().contains("超过付款金额"), e.getMessage());
    }

    @Test
    void requestRejectsAmountOverUnpaidInvoices() {
        FinPaymentRequestDao reqDao = mock(FinPaymentRequestDao.class);
        FinApInvoiceDao invoiceDao = mock(FinApInvoiceDao.class);
        FinApInvoice inv = new FinApInvoice();
        inv.setId("inv-junit");
        inv.setInvoiceNo("INV-JUNIT");
        inv.setSupplierId("sup-junit");
        inv.setStatus(FinApInvoice.ST_POSTED);
        inv.setTotalAmount(new BigDecimal("20000.00"));
        inv.setPaidAmount(BigDecimal.ZERO);
        when(invoiceDao.selectById("inv-junit")).thenReturn(inv);
        when(invoiceDao.selectCount(any())).thenReturn(0L);
        PaymentService svc = newPaymentService(reqDao, mock(FinPaymentDao.class), invoiceDao,
                mock(FinBankAccountDao.class), mock(ScarDeductionDao.class));

        Map<String, Object> p = new HashMap<>();
        p.put("supplierId", "sup-junit");
        p.put("applyAmount", "30000");
        p.put("invoiceIds", List.of("inv-junit"));
        ServiceException e = assertThrows(ServiceException.class, () -> svc.create(p));
        assertTrue(e.getMessage().contains("未清应付合计"), e.getMessage());
    }

    // ---------------- prepayment C-4.2-13 双 L1 ----------------

    @Test
    void prepaymentRejectsOverRatio() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("po-junit");
        po.setPoNo("PO-JUNIT");
        po.setSupplierId("sup-junit");
        po.setStatus("APPROVED");
        po.setTotalAmt(new BigDecimal("100000.00"));
        PurchaseOrderDao poDao = mock(PurchaseOrderDao.class);
        when(poDao.selectById("po-junit")).thenReturn(po);
        MdmSupplierDao supplierDao = mock(MdmSupplierDao.class);
        MdmSupplier sup = new MdmSupplier();
        sup.setStatus("QUALIFIED");
        when(supplierDao.selectById("sup-junit")).thenReturn(sup);
        PrepaymentService svc = newPrepaymentService(mock(FinPrepaymentDao.class), poDao,
                mock(GoodsReceiptDao.class), mock(GoodsReceiptLineDao.class), supplierDao);

        Map<String, Object> p = new HashMap<>();
        p.put("poId", "po-junit");
        p.put("applyAmount", "40000");   // 100000 × 30% = 30000 上限
        ServiceException e = assertThrows(ServiceException.class, () -> svc.create(p));
        assertTrue(e.getMessage().contains("预付比例"), e.getMessage());
    }

    @Test
    void prepaymentRejectsCumulativeOverPoOpen() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("po-junit");
        po.setPoNo("PO-JUNIT");
        po.setSupplierId("sup-junit");
        po.setStatus("APPROVED");
        po.setTotalAmt(new BigDecimal("100000.00"));
        PurchaseOrderDao poDao = mock(PurchaseOrderDao.class);
        when(poDao.selectById("po-junit")).thenReturn(po);
        FinPrepaymentDao ppDao = mock(FinPrepaymentDao.class);
        when(ppDao.selectPaidByPo("PO-JUNIT")).thenReturn(new BigDecimal("80000.00"));
        MdmSupplierDao supplierDao = mock(MdmSupplierDao.class);
        MdmSupplier sup = new MdmSupplier();
        sup.setStatus("QUALIFIED");
        when(supplierDao.selectById("sup-junit")).thenReturn(sup);
        PrepaymentService svc = newPrepaymentService(ppDao, poDao,
                mock(GoodsReceiptDao.class), mock(GoodsReceiptLineDao.class), supplierDao);

        Map<String, Object> p = new HashMap<>();
        p.put("poId", "po-junit");
        p.put("applyAmount", "30000");   // 比例恰好 30% 通过；累计 80000+30000 > 未清 100000
        ServiceException e = assertThrows(ServiceException.class, () -> svc.create(p));
        assertTrue(e.getMessage().contains("未清金额"), e.getMessage());
    }
}
