package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinPrepaymentDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.fin.FinPrepayment;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 预付款审批回调（BIZ_TYPE = Prepayment，spec prepayment）。
 * 通过 → `APPROVED`（待执行预付）；驳回 → `REJECTED`（可改重提）。与审批同事务。
 */
@Slf4j
@Component
public class PrepaymentApprovalCallback implements ApprovalCallback {

    private final FinPrepaymentDao ppDao;
    private final ApprovalTaskDao taskDao;

    public PrepaymentApprovalCallback(FinPrepaymentDao ppDao, ApprovalTaskDao taskDao) {
        this.ppDao = ppDao;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("Prepayment");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        FinPrepayment p = ppDao.selectById(instance.getBizId());
        if (p == null) {
            log.warn("prepayment approved but record missing: {}", instance.getBizId());
            return;
        }
        if (FinPrepayment.ST_APPROVED.equals(p.getStatus())) {
            return; // 幂等
        }
        if (!FinPrepayment.ST_PENDING.equals(p.getStatus())) {
            throw new ServiceException(422, "预付款状态不可批准：" + p.getStatus());
        }
        p.setStatus(FinPrepayment.ST_APPROVED);
        p.setApprovalId(instance.getId());
        if (ppDao.updateById(p) == 0) {
            throw new ServiceException(422, "预付款状态更新冲突");
        }
        log.info("prepayment {} approved（金额 {}），待执行", p.getPpNo(), p.getApplyAmount());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        FinPrepayment p = ppDao.selectById(instance.getBizId());
        if (p == null || FinPrepayment.ST_PAID.equals(p.getStatus())) {
            return;
        }
        ApprovalTask t = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1"));
        p.setStatus(FinPrepayment.ST_REJECTED);
        p.setApprovalId(instance.getId());
        p.setRejectReason(t != null && t.getOpinion() != null ? t.getOpinion() : "审批驳回");
        ppDao.updateById(p);
        log.info("prepayment {} rejected: {}", p.getPpNo(), p.getRejectReason());
    }
}
