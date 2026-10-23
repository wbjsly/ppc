package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.EmployeeLoanDao;
import com.erp.dao.fin.LoanWriteoffDao;
import com.erp.dao.fin.ReimburseDao;
import com.erp.dao.fin.ReimburseLineDao;
import com.erp.entity.fin.EmployeeLoan;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.fin.LoanWriteoff;
import com.erp.entity.fin.Reimburse;
import com.erp.entity.fin.ReimburseLine;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.fin.ExpenseTypeService;
import com.erp.service.fin.GlVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 费用报销审批回调（BIZ_TYPE = ExpenseReimburse，spec expense-reimbursement FR-4.6-7-5/6）。
 * onApproved 单事务（design D2/D3）：①报销凭证（借费用按映射聚合 / 贷 2241）；
 * ②FIFO 借款冲销（逐笔条件更新防并发，BR-4.6-01 MIN 保证全额冲销）+ 冲销凭证
 * （借 2241 / 贷 1299）+ 明细留痕；③状态回写（APPROVED + 差额结算状态）。
 * 任一步失败整体回滚；资金结算（补付/退款）不在此（决策⑤出纳手动）。
 */
@Slf4j
@Component
public class ExpenseReimburseApprovalCallback implements ApprovalCallback {

    static final String ACCT_PAYABLE_EMP = "2241";
    static final String ACCT_RECEIVABLE_EMP = "1299";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReimburseDao reimburseDao;
    private final ReimburseLineDao lineDao;
    private final EmployeeLoanDao loanDao;
    private final LoanWriteoffDao writeoffDao;
    private final ExpenseTypeService expenseTypeService;
    private final GlVoucherService voucherService;

    public ExpenseReimburseApprovalCallback(ReimburseDao reimburseDao, ReimburseLineDao lineDao,
                                            EmployeeLoanDao loanDao, LoanWriteoffDao writeoffDao,
                                            ExpenseTypeService expenseTypeService,
                                            GlVoucherService voucherService) {
        this.reimburseDao = reimburseDao;
        this.lineDao = lineDao;
        this.loanDao = loanDao;
        this.writeoffDao = writeoffDao;
        this.expenseTypeService = expenseTypeService;
        this.voucherService = voucherService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(ExpenseReimburseServiceImpl.BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        Reimburse r = reimburseDao.selectById(instance.getBizId());
        if (r == null) {
            log.warn("reimburse approved but missing: {}", instance.getBizId());
            return;
        }
        if (Reimburse.ST_APPROVED.equals(r.getStatus())) {
            return; // 幂等
        }
        List<ReimburseLine> lines = lineDao.selectList(new LambdaQueryWrapper<ReimburseLine>()
                .eq(ReimburseLine::getReimburseId, r.getId()));

        // ① 报销凭证：费用侧按科目聚合（映射解析缺失/停用 → 422 纵深防御，FR-4.6-7-5）
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        for (ReimburseLine ln : lines) {
            String acct = expenseTypeService.resolveAccountCode(ln.getExpenseType());
            byAccount.merge(acct, ln.getAmount(), BigDecimal::add);
        }
        List<GlVoucherService.FinVoucherLineSpec> voucherLines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : byAccount.entrySet()) {
            voucherLines.add(GlVoucherService.FinVoucherLineSpec.of(e.getKey(), "DR", e.getValue(),
                    "费用报销-" + r.getExpNo()));
        }
        voucherLines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_PAYABLE_EMP, "CR",
                r.getTotalAmount(), "其他应付款-员工报销-" + r.getEmployeeName()));
        LocalDate vDate = r.getOccurPeriod() != null && r.getOccurPeriod().length() == 6
                ? LocalDate.parse(r.getOccurPeriod().substring(0, 4) + "-"
                        + r.getOccurPeriod().substring(4, 6) + "-01")
                : LocalDate.now();
        FinVoucher v1 = voucherService.create("REIMB", vDate,
                "费用报销：" + r.getExpNo() + "（" + r.getEmployeeName() + "）",
                "REIMB", r.getExpNo(), null, voucherLines);

        // ② FIFO 借款冲销（FR-4.6-7-6 算法伪码；BR-4.6-01 由 MIN 天然保证）
        BigDecimal remaining = r.getTotalAmount();
        BigDecimal writtenTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<EmployeeLoan> fifo = loanDao.selectOpenFifo(r.getEmployeeId());
        for (EmployeeLoan loan : fifo) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal balance = loan.getOutstandingBalance() == null ? BigDecimal.ZERO : loan.getOutstandingBalance();
            if (balance.signum() <= 0) {
                continue;
            }
            BigDecimal take = remaining.min(balance);
            int updated = loanDao.update(null, new LambdaUpdateWrapper<EmployeeLoan>()
                    .eq(EmployeeLoan::getId, loan.getId())
                    .eq(EmployeeLoan::getStatus, EmployeeLoan.ST_PAID)
                    .apply("OUTSTANDING_BALANCE >= {0}", take)
                    .setSql("OUTSTANDING_BALANCE = OUTSTANDING_BALANCE - " + take.toPlainString())
                    .set(EmployeeLoan::getUpdateBy, "approval")
                    .set(EmployeeLoan::getUpdateDate, LocalDateTime.now()));
            if (updated == 0) {
                throw new ServiceException(422, "借款 " + loan.getLoanNo() + " 余额并发冲突，冲销回滚");
            }
            LoanWriteoff w = new LoanWriteoff();
            w.setId(uuid());
            w.setWoNo(nextWoNo());
            w.setReimburseId(r.getId());
            w.setReimburseNo(r.getExpNo());
            w.setLoanId(loan.getId());
            w.setLoanNo(loan.getLoanNo());
            w.setEmployeeId(r.getEmployeeId());
            w.setAmount(take);
            w.setWriteoffDate(LocalDate.now());
            w.setCreateBy("approval");
            w.setCreateDate(LocalDateTime.now());
            w.setVerNo(0);
            writeoffDao.insert(w);
            writtenTotal = writtenTotal.add(take);
            remaining = remaining.subtract(take);
        }

        // 冲销凭证：借 2241 / 贷 1299（FR-4.6-7-6；差额 = 报销额 − 冲销合计 > 0 时下方结算态承接）
        FinVoucher v2 = null;
        if (writtenTotal.signum() > 0) {
            v2 = voucherService.create("LWO", LocalDate.now(),
                    "借款冲销：" + r.getExpNo() + "（冲销 " + writtenTotal.toPlainString() + "）",
                    "REIMB", r.getExpNo(), null,
                    List.of(GlVoucherService.FinVoucherLineSpec.of(ACCT_PAYABLE_EMP, "DR", writtenTotal,
                            "其他应付款-员工报销-" + r.getEmployeeName()),
                            GlVoucherService.FinVoucherLineSpec.of(ACCT_RECEIVABLE_EMP, "CR", writtenTotal,
                                    "其他应收款-员工借款-" + r.getEmployeeName())));
        }

        // ③ 状态回写：APPROVED + 差额结算状态（>0 待出纳补付；=0 闭环）
        BigDecimal diff = r.getTotalAmount().subtract(writtenTotal).setScale(2, RoundingMode.HALF_UP);
        r.setStatus(Reimburse.ST_APPROVED);
        r.setVoucherId(v1.getId());
        r.setSettleStatus(diff.signum() > 0 ? Reimburse.SETTLE_PENDING : Reimburse.SETTLE_DONE);
        r.setUpdateBy("approval");
        r.setUpdateDate(LocalDateTime.now());
        if (reimburseDao.updateById(r) == 0) {
            throw new ServiceException(409, "报销单状态更新冲突：" + r.getExpNo());
        }
        // 剩余借款 > 0 → 提示归还（FR-4.6-7-6 异常处理「剩余借款保持挂账并提醒归还」）
        long openLoans = loanDao.selectCount(new LambdaQueryWrapper<EmployeeLoan>()
                .eq(EmployeeLoan::getEmployeeId, r.getEmployeeId())
                .eq(EmployeeLoan::getStatus, EmployeeLoan.ST_PAID)
                .gt(EmployeeLoan::getOutstandingBalance, BigDecimal.ZERO));
        log.info("reimburse {} approved: voucher={} writeoff={} diff={} openLoans={} by approval",
                r.getExpNo(), v1.getVoucherNo(), writtenTotal, diff, openLoans);
        if (openLoans > 0) {
            log.info("[NOTICE] 员工 {} 尚有 {} 笔未核销借款待归还（含本次报销后余额）",
                    r.getEmployeeName(), openLoans);
        }
        if (v2 != null) {
            log.info("loan writeoff voucher {} for {}", v2.getVoucherNo(), r.getExpNo());
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        Reimburse r = reimburseDao.selectById(instance.getBizId());
        if (r == null || Reimburse.ST_APPROVED.equals(r.getStatus())) {
            return;
        }
        r.setStatus(Reimburse.ST_REJECTED);
        r.setUpdateBy("approval");
        r.setUpdateDate(LocalDateTime.now());
        reimburseDao.updateById(r);
        log.info("reimburse {} rejected (no voucher)", r.getExpNo());
    }

    private String nextWoNo() {
        String prefix = "LWO" + LocalDate.now().format(DAY);
        Integer max = writeoffDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
