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
 * BOM 发布审批回调（change add-bom-management，spec bom-management FR-4.5-1-6，任务 5.1）。
 * bizType = BomPublish；与审批签署同事务（ApprovalEngine 契约）：回调异常 → 签署与业务一并回滚。
 * 通过 → BomService.publishApproved（PENDING→PUBLISHED + 旧版 REVISED + 发布留痕）；
 * 驳回 → BomService.rejectBackToDraft（PENDING→DRAFT，驳回意见取自被拒节点回填 REJECT_REASON）。
 * BomService 以 @Lazy 注入：ApprovalServiceImpl 构造期收集本回调，硬依赖会形成
 * 构造器循环（回调 → 服务 → 审批底座 → 回调），延迟代理断环。
 */
@Slf4j
@Component
public class BomApprovalCallback implements ApprovalCallback {

    private final BomService bomService;
    private final ApprovalTaskDao taskDao;

    public BomApprovalCallback(@Lazy BomService bomService, ApprovalTaskDao taskDao) {
        this.bomService = bomService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BomService.BIZ_PUBLISH);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        bomService.publishApproved(instance.getBizId());
        log.info("BOM 发布审批通过：bizId={} apprNo={}", instance.getBizId(), instance.getApprNo());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        // 驳回意见由底座留痕在被拒节点，取回填入 BOM 版本（REJECT_REASON）
        List<ApprovalTask> rejected = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED"));
        String opinion = rejected.isEmpty() ? null : rejected.get(0).getOpinion();
        bomService.rejectBackToDraft(instance.getBizId(), opinion);
        log.info("BOM 发布审批驳回：bizId={} 意见={}", instance.getBizId(), opinion);
    }
}
