package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mrp.TrialApplyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 试产申请审批回调（change add-trial-production-management，spec trial-order
 * 「试产申请单建档与校验」FR-4.5-8-1）。
 * bizId = 申请单号（TA-）；通过 → APPROVED；驳回 → 回 DRAFT 留痕意见可重提。
 * 驳回意见由底座留痕在被拒节点，取回填（MoApprovalCallback 同范式——
 * ApprovalServiceImpl 构造期收集本回调，@Lazy 注入服务断构造器循环）。
 */
@Slf4j
@Component
public class TrialApplyCallback implements ApprovalCallback {

    private final TrialApplyService trialApplyService;
    private final ApprovalTaskDao taskDao;
    private final com.erp.service.system.NoticeService noticeService;

    public TrialApplyCallback(@org.springframework.context.annotation.Lazy TrialApplyService trialApplyService,
                              ApprovalTaskDao taskDao,
                              com.erp.service.system.NoticeService noticeService) {
        this.trialApplyService = trialApplyService;
        this.taskDao = taskDao;
        this.noticeService = noticeService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(TrialApplyServiceImpl.BIZ_TRIAL_APPLY);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        trialApplyService.onApproval(instance.getBizId(), true,
                instance.getRemark(), instance.getApplyBy());
        noticeService.push(null, instance.getApplyBy(),
                "试产申请审批通过", "试产申请 " + instance.getBizId() + " 审批 "
                        + instance.getApprNo() + " 已通过，可转开试产工单",
                "TRIAL_APPLY_RESULT", instance.getBizId());
        log.info("TrialApply {} approved: {}", instance.getApprNo(), instance.getBizId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        String opinion = rejectedOpinion(instance.getId());
        trialApplyService.onApproval(instance.getBizId(), false, opinion, instance.getApplyBy());
        noticeService.push(null, instance.getApplyBy(),
                "试产申请审批驳回", "试产申请 " + instance.getBizId() + " 被驳回（"
                        + nvl(opinion) + "），修改后可重新提交",
                "TRIAL_APPLY_RESULT", instance.getBizId());
        log.info("TrialApply {} rejected: {} 意见={}", instance.getApprNo(),
                instance.getBizId(), opinion);
    }

    /** 驳回意见取自被拒节点（底座不回填 instance.remark） */
    private String rejectedOpinion(String apprId) {
        List<ApprovalTask> rejected = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, apprId)
                .eq(ApprovalTask::getStatus, "REJECTED"));
        return rejected.isEmpty() ? null : rejected.get(0).getOpinion();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
