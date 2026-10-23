package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 技改判定复核审批回调（add-asset-rework，BIZ_TYPE = AssetRework）。
 * 通过 → 判定推进 JUDGED；驳回 → 退回 DRAFT 留因重判。
 */
@Slf4j
@Component
public class AssetReworkCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamReworkService reworkService;
    private final com.erp.dao.system.ApprovalTaskDao taskDao;

    public AssetReworkCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamReworkService reworkService,
            com.erp.dao.system.ApprovalTaskDao taskDao) {
        this.reworkService = reworkService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetRework");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        reworkService.onApprovalPassed(instance.getBizId());
        log.info("技改单 {} 判定复核通过（推进 JUDGED）", instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        String reason = "判定复核驳回";
        java.util.List<com.erp.entity.system.ApprovalTask> rejected = taskDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.erp.entity.system.ApprovalTask>()
                        .eq(com.erp.entity.system.ApprovalTask::getApprId, instance.getId())
                        .eq(com.erp.entity.system.ApprovalTask::getStatus, "REJECTED"));
        if (!rejected.isEmpty() && rejected.get(0).getOpinion() != null
                && !rejected.get(0).getOpinion().isBlank()) {
            reason = rejected.get(0).getOpinion();
        }
        reworkService.onApprovalRejected(instance.getBizId(), reason);
        log.info("技改单 {} 判定复核驳回（{}）", instance.getBizId(), reason);
    }
}
