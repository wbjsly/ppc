package com.erp.service.impl.fin;

import com.erp.dao.fin.EmployeeLoanDao;
import com.erp.entity.fin.EmployeeLoan;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.fin.GlVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 员工借款审批回调（BIZ_TYPE = EmployeeLoan，spec expense-reimbursement FR-4.6-7-2）。
 * 通过 → 挂账凭证（借 1299 其他应收款-员工借款 / 贷 1002 银行存款，自动过账）+
 * 置已放款、回写未核销余额；驳回 → 置 REJECTED 无账务。
 * design D2：资金动作不在此（借款放款是审批通过即发生，凭证自动过账；报销差额结算才是出纳手动）。
 */
@Slf4j
@Component
public class EmployeeLoanApprovalCallback implements ApprovalCallback {

    static final String ACCT_LOAN = "1299";
    static final String ACCT_BANK = "1002";

    private final EmployeeLoanDao loanDao;
    private final GlVoucherService voucherService;

    public EmployeeLoanApprovalCallback(EmployeeLoanDao loanDao, GlVoucherService voucherService) {
        this.loanDao = loanDao;
        this.voucherService = voucherService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(EmployeeLoanServiceImpl.BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        EmployeeLoan loan = loanDao.selectById(instance.getBizId());
        if (loan == null) {
            log.warn("employee loan approved but missing: {}", instance.getBizId());
            return;
        }
        if (EmployeeLoan.ST_PAID.equals(loan.getStatus())) {
            return; // 幂等（重签/重放防护）
        }
        BigDecimal amount = loan.getAmount();
        FinVoucher v = voucherService.create("LOAN", LocalDate.now(),
                "员工借款挂账：" + loan.getLoanNo() + "（" + loan.getEmployeeName() + "）",
                "LOAN", loan.getLoanNo(), null,
                List.of(GlVoucherService.FinVoucherLineSpec.of(ACCT_LOAN, "DR", amount,
                        "其他应收款-员工借款-" + loan.getEmployeeName()),
                        GlVoucherService.FinVoucherLineSpec.of(ACCT_BANK, "CR", amount,
                                "银行存款-借款放款")));
        loan.setStatus(EmployeeLoan.ST_PAID);
        loan.setPaidDate(LocalDate.now());
        loan.setOutstandingBalance(amount);
        loan.setVoucherId(v.getId());
        loan.setUpdateBy("approval");
        loan.setUpdateDate(java.time.LocalDateTime.now());
        if (loanDao.updateById(loan) == 0) {
            throw new com.erp.common.ServiceException(409, "借款单状态更新冲突：" + loan.getLoanNo());
        }
        log.info("employee loan {} posted: {} voucher={}", loan.getLoanNo(), amount, v.getVoucherNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        EmployeeLoan loan = loanDao.selectById(instance.getBizId());
        if (loan == null || EmployeeLoan.ST_PAID.equals(loan.getStatus())) {
            return;
        }
        loan.setStatus(EmployeeLoan.ST_REJECTED);
        loan.setUpdateBy("approval");
        loan.setUpdateDate(java.time.LocalDateTime.now());
        loanDao.updateById(loan);
        log.info("employee loan {} rejected", loan.getLoanNo());
    }
}
