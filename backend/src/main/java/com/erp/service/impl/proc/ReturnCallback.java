package com.erp.service.impl.proc;

import com.erp.common.ServiceException;
import com.erp.dao.proc.ReturnOrderDao;
import com.erp.entity.proc.ReturnOrder;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 退货审批回调（BIZ_TYPE = Return，spec quality-return，tasks 8.2）。
 * 通过 → APPROVED（可出库）；驳回 → REJECTED（可改重提）。与审批同事务。
 */
@Slf4j
@Component
public class ReturnCallback implements ApprovalCallback {

    private final ReturnOrderDao returnDao;
    private final ApprovalTaskDao taskDao;

    public ReturnCallback(ReturnOrderDao returnDao, ApprovalTaskDao taskDao) {
        this.returnDao = returnDao;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("Return");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        ReturnOrder r = returnDao.selectById(instance.getBizId());
        if (r == null) {
            log.warn("Return approved but record missing: {}", instance.getBizId());
            return;
        }
        if ("APPROVED".equals(r.getStatus())) {
            return; // 幂等
        }
        if (!"PENDING_APPROVE".equals(r.getStatus())) {
            throw new ServiceException(422, "退货单状态不可批准：" + r.getStatus());
        }
        r.setStatus("APPROVED");
        r.setApprovalId(instance.getId());
        if (returnDao.updateById(r) == 0) {
            throw new ServiceException(422, "退货单状态更新冲突");
        }
        log.info("return {} approved by PM", r.getReturnNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        ReturnOrder r = returnDao.selectById(instance.getBizId());
        if (r == null || "OUT_DONE".equals(r.getStatus())) {
            return;
        }
        ApprovalTask t = taskDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1"));
        String reason = t == null ? null : t.getOpinion();
        r.setStatus("REJECTED");
        r.setApprovalId(instance.getId());
        r.setRejectReason(hasText(reason) ? reason : "采购经理驳回");
        returnDao.updateById(r);
        log.info("return {} rejected: {}", r.getReturnNo(), r.getRejectReason());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
