package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 调拨审批回调（add-asset-transfer-lease，BIZ_TYPE = AssetTransfer，design D2）。
 * 通过 → 置 APPROVED 待实物交接双确认（完成由资产管理员在交接双确认后显式触发，
 * 与 spec「审批通过后 MUST 经实物交接双确认方可进入系统完成」一致）；
 * 驳回 → 回 DRAFT 留因（可改重提）。
 */
@Slf4j
@Component
public class AssetTransferCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamTransferService transferService;
    private final com.erp.dao.system.ApprovalTaskDao taskDao;

    public AssetTransferCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamTransferService transferService,
            com.erp.dao.system.ApprovalTaskDao taskDao) {
        this.transferService = transferService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetTransfer");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        transferService.onApprovalPassed(instance.getBizId());
        log.info("调拨单 {} 审批通过 → 待交接双确认", instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        // 驳回意见落在 REJECTED 任务节点上（spec：驳回原因留痕）
        String reason = "审核驳回";
        java.util.List<com.erp.entity.system.ApprovalTask> rejected =
                taskDao.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.erp.entity.system.ApprovalTask>()
                        .eq(com.erp.entity.system.ApprovalTask::getApprId, instance.getId())
                        .eq(com.erp.entity.system.ApprovalTask::getStatus, "REJECTED"));
        if (!rejected.isEmpty() && rejected.get(0).getOpinion() != null
                && !rejected.get(0).getOpinion().isBlank()) {
            reason = rejected.get(0).getOpinion();
        }
        transferService.onApprovalRejected(instance.getBizId(), reason);
        log.info("调拨单 {} 审批驳回（{}）→ 回可改态", instance.getBizId(), reason);
    }
}
