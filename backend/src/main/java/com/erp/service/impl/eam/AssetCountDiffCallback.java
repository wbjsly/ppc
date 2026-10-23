package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 盘点差异审批回调（add-asset-count，BIZ_TYPE = AssetCountDiff，design D2）。
 * 通过 → 盘盈置 READY_TO_POST / 盘亏挂账置 LIABILITY_PENDING（BR-28 两段式）/
 * 冻结类直接 CLOSED；驳回 → 回调查态留因。
 */
@Slf4j
@Component
public class AssetCountDiffCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamCountDiffService diffService;
    private final com.erp.dao.system.ApprovalTaskDao taskDao;

    public AssetCountDiffCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamCountDiffService diffService,
            com.erp.dao.system.ApprovalTaskDao taskDao) {
        this.diffService = diffService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetCountDiff");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        diffService.onApprovalPassed(instance.getBizId());
        log.info("盘点差异单 {} 审批通过", instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        // 驳回意见落在 REJECTED 任务节点上（留痕）
        String reason = "差异审批驳回";
        java.util.List<ApprovalTask> rejected = taskDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getApprId, instance.getId())
                        .eq(ApprovalTask::getStatus, "REJECTED"));
        if (!rejected.isEmpty() && rejected.get(0).getOpinion() != null
                && !rejected.get(0).getOpinion().isBlank()) {
            reason = rejected.get(0).getOpinion();
        }
        diffService.onApprovalRejected(instance.getBizId(), reason);
        log.info("盘点差异单 {} 审批驳回（{}）", instance.getBizId(), reason);
    }
}
