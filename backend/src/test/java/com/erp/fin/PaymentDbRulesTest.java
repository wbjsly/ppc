package com.erp.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.FinPaymentWriteoffDao;
import com.erp.dao.fin.FinPrepaymentDao;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.FinPaymentWriteoff;
import com.erp.entity.fin.FinPrepayment;
import com.erp.service.fin.PrepaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 付款域数据库侧语义（真实 MySQL，spec bank-account / payment-execution / prepayment）：
 * ① 账户余额条件扣减不透支（并发语义）② 发票 PAID_AMOUNT 条件增量防超核销
 * ③ 预付冲抵 min() 边界（预付少于应付 / 已冲平幂等）。
 */
@SpringBootTest
class PaymentDbRulesTest {

    @Autowired
    private FinBankAccountDao bankDao;
    @Autowired
    private FinApInvoiceDao invoiceDao;
    @Autowired
    private FinPaymentWriteoffDao woDao;
    @Autowired
    private FinPrepaymentDao ppDao;
    @Autowired
    private PrepaymentService prepaymentService;

    // @TableLogic 为软删，重跑/多方法共用 ID 会主键冲突 → 每次用例独立 ID
    private String accountId;
    private String invoiceId;
    private String ppId;

    @org.junit.jupiter.api.BeforeEach
    void freshIds() {
        String suffix = "-" + System.nanoTime();
        accountId = "bank-junit-db" + suffix;
        invoiceId = "inv-junit-db" + suffix;
        ppId = "pp-junit-db" + suffix;
    }

    @AfterEach
    void cleanup() {
        woDao.delete(new LambdaQueryWrapper<FinPaymentWriteoff>().eq(FinPaymentWriteoff::getPpId, ppId));
        ppDao.deleteById(ppId);
        invoiceDao.deleteById(invoiceId);
        bankDao.deleteById(accountId);
    }

    @Test
    void bankBalanceConditionalDeductNeverGoesNegative() {
        FinBankAccount acc = new FinBankAccount();
        acc.setId(accountId);
        acc.setAccountName("JUnit 条件扣减户");
        acc.setAccountNo("JUNITDB" + (System.nanoTime() % 100000000));
        acc.setCurrency("CNY");
        acc.setBalance(new BigDecimal("100.00"));
        acc.setStatus(FinBankAccount.ST_ACTIVE);
        bankDao.insert(acc);

        // 超余额扣减不生效（并发/不足语义 → 0 行）
        assertEquals(0, bankDao.deductBalance(accountId, new BigDecimal("150.00")));
        assertEquals(0, new BigDecimal("100.00")
                .compareTo(bankDao.selectById(accountId).getBalance()));

        // 余额充足扣减成功
        assertEquals(1, bankDao.deductBalance(accountId, new BigDecimal("60.00")));
        assertEquals(0, new BigDecimal("40.00")
                .compareTo(bankDao.selectById(accountId).getBalance()));

        // 剩余 40 再扣 50 → 0 行且余额不为负（不透支）
        assertEquals(0, bankDao.deductBalance(accountId, new BigDecimal("50.00")));
        BigDecimal after = bankDao.selectById(accountId).getBalance();
        assertEquals(0, new BigDecimal("40.00").compareTo(after));
        assertEquals(1, after.signum());   // 余额为正
    }

    @Test
    void invoicePaidAmountConditionalNeverExceedsTotal() {
        insertInvoice("100.00");

        // 超未清核销不生效
        assertEquals(0, conditionalPay(new BigDecimal("120.00")));
        assertEquals(0, new BigDecimal("0.00").compareTo(paid()));

        // 正常核销 60（未清 40）
        assertEquals(1, conditionalPay(new BigDecimal("60.00")));
        assertEquals(0, new BigDecimal("60.00").compareTo(paid()));

        // 剩余 40 再核销 50 → 0 行（防超核销）
        assertEquals(0, conditionalPay(new BigDecimal("50.00")));
        assertEquals(0, new BigDecimal("60.00").compareTo(paid()));
    }

    @Test
    void prepaySettleRespectsMinBoundaryAndIdempotency() {
        insertInvoice("100.00");
        FinPrepayment p = new FinPrepayment();
        p.setId(ppId);
        p.setPpNo("PPJUNIT" + (System.nanoTime() % 1000000));
        p.setPoId("po-junit-db");
        p.setPoNo("PO-JUNIT-DB");
        p.setSupplierId("sup-junit-db");
        p.setSupplierName("JUnit 供应商");
        p.setPrepayRatio(new BigDecimal("0.3000"));
        p.setApplyAmount(new BigDecimal("30.00"));
        p.setPoTotalAmt(new BigDecimal("100.00"));
        p.setPoOpenAmt(new BigDecimal("100.00"));
        p.setStatus(FinPrepayment.ST_PAID);
        p.setExecutedAmount(new BigDecimal("30.00"));
        p.setSettledAmount(BigDecimal.ZERO);
        ppDao.insert(p);

        // 预付 30 < 应付 100 → 冲 30（min 边界），发票已核销升至 30、未清余 70
        int n = prepaymentService.settleForPo("PO-JUNIT-DB");
        assertEquals(1, n);
        assertEquals(0, new BigDecimal("30.00").compareTo(
                ppDao.selectById(ppId).getSettledAmount()));
        assertEquals(0, new BigDecimal("30.00").compareTo(
                invoiceDao.selectById(invoiceId).getPaidAmount()));
        assertEquals(1, woDao.selectCount(new LambdaQueryWrapper<FinPaymentWriteoff>()
                .eq(FinPaymentWriteoff::getPpId, ppId)));

        // 预付已冲平 → 幂等 0 笔，不重复生成记录
        assertEquals(0, prepaymentService.settleForPo("PO-JUNIT-DB"));
        assertEquals(1, woDao.selectCount(new LambdaQueryWrapper<FinPaymentWriteoff>()
                .eq(FinPaymentWriteoff::getPpId, ppId)));
    }

    private void insertInvoice(String total) {
        FinApInvoice inv = new FinApInvoice();
        inv.setId(invoiceId);
        inv.setInvoiceNo("INV-JUNIT-DB-" + (System.nanoTime() % 1000000));
        inv.setSupplierId("sup-junit-db");
        inv.setSupplierName("JUnit 供应商");
        inv.setInvoiceDate(LocalDate.now());
        inv.setCurrency("CNY");
        inv.setTotalAmount(new BigDecimal(total));
        inv.setPaidAmount(BigDecimal.ZERO);
        inv.setPoNo("PO-JUNIT-DB");
        inv.setStatus(FinApInvoice.ST_POSTED);
        invoiceDao.insert(inv);
        assertNotNull(invoiceDao.selectById(invoiceId));
    }

    private int conditionalPay(BigDecimal amt) {
        return invoiceDao.update(null, new LambdaUpdateWrapper<FinApInvoice>()
                .eq(FinApInvoice::getId, invoiceId)
                .eq(FinApInvoice::getStatus, FinApInvoice.ST_POSTED)
                .apply("PAID_AMOUNT + {0} <= TOTAL_AMOUNT", amt)
                .setSql("PAID_AMOUNT = PAID_AMOUNT + " + amt.toPlainString()));
    }

    private BigDecimal paid() {
        return invoiceDao.selectById(invoiceId).getPaidAmount();
    }
}
