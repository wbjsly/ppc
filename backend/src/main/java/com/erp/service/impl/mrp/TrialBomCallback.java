package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mrp.BomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 试产版 BOM 特别审批回调（change add-trial-production-management，spec bom-management delta，
 * FR-4.5-8-2「经工艺主管特别审批后生效」）。
 * bizType = TrialBom；通过 → BomService.onTrialApproved（DRAFT→TRIAL + 旧 TRIAL→REVISED，同事务 CAS）；
 * 驳回 → 状态保持 DRAFT + 意见留痕（被拒节点取回填，MoApprovalCallback 同范式）。
 * BomService 以 @Lazy 注入断审批底座构造器循环（BomApprovalCallback 同范式）。
 */
@Slf4j
@Component
public class TrialBomCallback implements ApprovalCallback {

    private final BomService bomService;
    private final ApprovalTaskDao taskDao;

    public TrialBomCallback(@Lazy BomService bomService, ApprovalTaskDao taskDao) {
        this.bomService = bomService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BomService.BIZ_TRIAL);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        bomService.onTrialApproved(instance.getBizId());
        log.info("试产版 BOM 审批通过：bizId={} apprNo={}", instance.getBizId(), instance.getApprNo());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        List<ApprovalTask> rejected = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED"));
        String opinion = rejected.isEmpty() ? null : rejected.get(0).getOpinion();
        bomService.onTrialRejected(instance.getBizId(), opinion);
        log.info("试产版 BOM 审批驳回：bizId={} 意见={}", instance.getBizId(), opinion);
    }
}
