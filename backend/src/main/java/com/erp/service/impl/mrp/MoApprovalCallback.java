package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mrp.MoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 工单审批回调（change add-work-order-management，spec「工单审批状态机」，任务 5.1）。
 * bizType = MoApprove；与审批签署同事务（ApprovalEngine 契约）：回调异常 → 签署与业务一并回滚。
 * 通过 → MoService.onApproved（PENDING→CONFIRMED + 留痕）；
 * 驳回 → MoService.onRejected（PENDING→PLANNED，意见取自被拒节点回填）。
 * MoService 以 @Lazy 注入：ApprovalServiceImpl 构造期收集本回调，硬依赖会形成
 * 构造器循环（回调 → 服务 → 审批底座 → 回调），延迟代理断环（BomApprovalCallback 同范式）。
 */
@Slf4j
@Component
public class MoApprovalCallback implements ApprovalCallback {

    private final MoService moService;
    private final ApprovalTaskDao taskDao;

    public MoApprovalCallback(@Lazy MoService moService, ApprovalTaskDao taskDao) {
        this.moService = moService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(MoService.BIZ_APPROVE);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        moService.onApproved(instance.getBizId());
        log.info("工单审批通过：bizId={} apprNo={}", instance.getBizId(), instance.getApprNo());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        // 驳回意见由底座留痕在被拒节点，取回填入工单（REJECT_REASON）
        List<ApprovalTask> rejected = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED"));
        String opinion = rejected.isEmpty() ? null : rejected.get(0).getOpinion();
        moService.onRejected(instance.getBizId(), opinion);
        log.info("工单审批驳回：bizId={} 意见={}", instance.getBizId(), opinion);
    }
}
