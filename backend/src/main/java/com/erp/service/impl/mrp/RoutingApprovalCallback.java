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
 * 工艺路线发布审批回调（change add-routing-management，spec routing-management
 * 「审核发布复用审批底座」，任务 5.1）。
 * bizType = RoutingPublish；与审批签署同事务（ApprovalEngine 契约）：回调异常 → 签署与业务一并回滚。
 * 通过 → RoutingService.publishApproved（PENDING→PUBLISHED + 旧版 REVISED + 发布留痕）；
 * 驳回 → RoutingService.rejectBackToDraft（PENDING→DRAFT，意见取自被拒节点回填 REJECT_REASON）。
 * RoutingService 以 @Lazy 注入：与 BomApprovalCallback 同构，断构造器循环。
 */
@Slf4j
@Component
public class RoutingApprovalCallback implements ApprovalCallback {

    private final RoutingService routingService;
    private final ApprovalTaskDao taskDao;

    public RoutingApprovalCallback(@Lazy RoutingService routingService, ApprovalTaskDao taskDao) {
        this.routingService = routingService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(RoutingService.BIZ_PUBLISH);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        routingService.publishApproved(instance.getBizId());
        log.info("路线发布审批通过：bizId={} apprNo={}", instance.getBizId(), instance.getApprNo());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        List<ApprovalTask> rejected = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED"));
        String opinion = rejected.isEmpty() ? null : rejected.get(0).getOpinion();
        routingService.rejectBackToDraft(instance.getBizId(), opinion);
        log.info("路线发布审批驳回：bizId={} 意见={}", instance.getBizId(), opinion);
    }
}
