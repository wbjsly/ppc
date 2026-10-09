package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinPaymentRequestDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.fin.FinPaymentRequest;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 付款审批回调（BIZ_TYPE = PaymentRequest，spec payment-request）。
 * 通过 → `APPROVED`（进入排期）；驳回 → `REJECTED`（可改重提）。与审批同事务（缺回调=审批过单据不动）。
 */
@Slf4j
@Component
public class PaymentApprovalCallback implements ApprovalCallback {

    private final FinPaymentRequestDao reqDao;
    private final ApprovalTaskDao taskDao;

    public PaymentApprovalCallback(FinPaymentRequestDao reqDao, ApprovalTaskDao taskDao) {
        this.reqDao = reqDao;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("PaymentRequest");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        FinPaymentRequest r = reqDao.selectById(instance.getBizId());
        if (r == null) {
            log.warn("payment request approved but record missing: {}", instance.getBizId());
            return;
        }
        if (FinPaymentRequest.ST_APPROVED.equals(r.getStatus())) {
            return; // 幂等
        }
        if (!FinPaymentRequest.ST_PENDING.equals(r.getStatus())) {
            throw new ServiceException(422, "付款申请状态不可批准：" + r.getStatus());
        }
        r.setStatus(FinPaymentRequest.ST_APPROVED);
        r.setApprovalId(instance.getId());
        if (reqDao.updateById(r) == 0) {
            throw new ServiceException(422, "付款申请状态更新冲突");
        }
        log.info("payment request {} approved（金额 {}），待排期", r.getReqNo(), r.getApplyAmount());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        FinPaymentRequest r = reqDao.selectById(instance.getBizId());
        if (r == null || FinPaymentRequest.ST_SCHEDULED.equals(r.getStatus())
                || FinPaymentRequest.ST_PAID.equals(r.getStatus())) {
            return;
        }
        ApprovalTask t = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1"));
        r.setStatus(FinPaymentRequest.ST_REJECTED);
        r.setApprovalId(instance.getId());
        r.setRejectReason(t != null && t.getOpinion() != null ? t.getOpinion() : "审批驳回");
        reqDao.updateById(r);
        log.info("payment request {} rejected: {}", r.getReqNo(), r.getRejectReason());
    }
}
