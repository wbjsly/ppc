package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mrp.TrialSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 试产总结评审回调（change add-trial-production-management，spec trial-execution
 * 「试产总结与跨部门评审」FR-4.5-8-6 / design D10）。
 * bizId = 总结单号（TS-）；三并行节点全过 → APPROVED（阶段闸口与转量产解锁依据）；
 * 任一驳回 → REJECTED 留痕意见可改后重提。
 */
@Slf4j
@Component
public class TrialReviewCallback implements ApprovalCallback {

    private final TrialSummaryService trialSummaryService;
    private final ApprovalTaskDao taskDao;
    private final com.erp.service.system.NoticeService noticeService;

    public TrialReviewCallback(@org.springframework.context.annotation.Lazy TrialSummaryService trialSummaryService,
                               ApprovalTaskDao taskDao,
                               com.erp.service.system.NoticeService noticeService) {
        this.trialSummaryService = trialSummaryService;
        this.taskDao = taskDao;
        this.noticeService = noticeService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(TrialSummaryService.BIZ_TRIAL_REVIEW);
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        trialSummaryService.onApproval(instance.getBizId(), true,
                instance.getRemark(), instance.getApplyBy());
        noticeService.push(null, instance.getApplyBy(),
                "试产总结评审通过", "试产总结 " + instance.getBizId() + " 跨部门评审 "
                        + instance.getApprNo() + " 已通过（工艺/质量/计划三方）",
                "TRIAL_REVIEW_RESULT", instance.getBizId());
        log.info("TrialReview {} approved: {}", instance.getApprNo(), instance.getBizId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        String opinion = rejectedOpinion(instance.getId());
        trialSummaryService.onApproval(instance.getBizId(), false, opinion, instance.getApplyBy());
        noticeService.push(null, instance.getApplyBy(),
                "试产总结评审驳回", "试产总结 " + instance.getBizId() + " 被驳回（"
                        + (opinion == null ? "" : opinion) + "），修改后可重新提交",
                "TRIAL_REVIEW_RESULT", instance.getBizId());
        log.info("TrialReview {} rejected: {} 意见={}", instance.getApprNo(),
                instance.getBizId(), opinion);
    }

    /** 驳回意见取自被拒节点（底座不回填 instance.remark） */
    private String rejectedOpinion(String apprId) {
        List<ApprovalTask> rejected = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, apprId)
                .eq(ApprovalTask::getStatus, "REJECTED"));
        return rejected.isEmpty() ? null : rejected.get(0).getOpinion();
    }
}
