package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mrp.RoutingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 试产版路线特别审批回调（change add-trial-production-management，spec routing-management delta，
 * FR-4.5-8-2「经工艺主管特别审批后生效」）。
 * bizType = TrialRoute；通过 → RoutingService.onTrialApproved（DRAFT→TRIAL + 旧 TRIAL→REVISED，同事务 CAS）；
 * 驳回 → 状态保持 DRAFT + 意见留痕。@Lazy 断审批底座构造器循环（TrialBomCallback 同范式）。
 */
@Slf4j
@Component
public class TrialRouteCallback implements ApprovalCallback {

    private final RoutingService routingService;
    private final ApprovalTaskDao taskDao;

    public TrialRouteCallback(@Lazy RoutingService routingService, ApprovalTaskDao taskDao) {
        this.routingService = routingService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(RoutingService.BIZ_TRIAL);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        routingService.onTrialApproved(instance.getBizId());
        log.info("试产版路线审批通过：bizId={} apprNo={}", instance.getBizId(), instance.getApprNo());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        List<ApprovalTask> rejected = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED"));
        String opinion = rejected.isEmpty() ? null : rejected.get(0).getOpinion();
        routingService.onTrialRejected(instance.getBizId(), opinion);
        log.info("试产版路线审批驳回：bizId={} 意见={}", instance.getBizId(), opinion);
    }
}
