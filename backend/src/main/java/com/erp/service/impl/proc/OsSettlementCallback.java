package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.proc.OsSettlement;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.proc.OsSettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 外协对账审批回调（change add-outsourcing-processing，spec outsourcing-settlement
 * 「对账确认与应付挂账」；approval-workflow 同事务回调）。
 * bizId = 对账单 ID；通过 → CONFIRMED → POSTED + 生成 OPEN 应付挂账；
 * 驳回 → 回 DRAFT（行保留，引用从审批锁释放，可改后重新提交）；驳回意见取被拒节点 opinion。
 */
@Slf4j
@Component
public class OsSettlementCallback implements ApprovalCallback {

    private final OsSettlementService service;
    private final ApprovalTaskDao taskDao;

    public OsSettlementCallback(OsSettlementService service, ApprovalTaskDao taskDao) {
        this.service = service;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(OsSettlement.BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        service.approve(instance.getBizId(), instance.getApprNo());
        log.info("OutsourceSettlement {} approved: {}", instance.getApprNo(), instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        // 驳回意见取被拒节点的签署意见（与 ConcessionCallback 同口径）
        ApprovalTask task = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1"));
        String reason = task == null ? null : task.getOpinion();
        service.reject(instance.getBizId(), instance.getApprNo(), reason);
        log.info("OutsourceSettlement {} rejected: {}", instance.getApprNo(), instance.getBizId());
    }
}
