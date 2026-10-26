package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 资产处置审批回调（add-asset-disposal，BIZ_TYPE = AssetDisposal，design D2/C-4.13-03）。
 * 通过 → 段1 转清理凭证自动生成（失败连带回滚审批推进）+ 状态推进 APPROVED；驳回 → REJECTED 留因。
 */
@Slf4j
@Component
public class AssetDisposalCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamDisposalService disposalService;
    private final com.erp.dao.system.ApprovalTaskDao taskDao;

    public AssetDisposalCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamDisposalService disposalService,
            com.erp.dao.system.ApprovalTaskDao taskDao) {
        this.disposalService = disposalService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetDisposal");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        disposalService.onApprovalPassed(instance.getBizId());
        log.info("处置单 {} 审批通过（段1 转清理已生成）", instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        String reason = "处置审批驳回";
        java.util.List<com.erp.entity.system.ApprovalTask> rejected = taskDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.erp.entity.system.ApprovalTask>()
                        .eq(com.erp.entity.system.ApprovalTask::getApprId, instance.getId())
                        .eq(com.erp.entity.system.ApprovalTask::getStatus, "REJECTED"));
        if (!rejected.isEmpty() && rejected.get(0).getOpinion() != null
                && !rejected.get(0).getOpinion().isBlank()) {
            reason = rejected.get(0).getOpinion();
        }
        disposalService.onApprovalRejected(instance.getBizId(), reason);
        log.info("处置单 {} 审批驳回（{}）", instance.getBizId(), reason);
    }
}
