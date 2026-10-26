package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.qms.NcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * CTQ 处置审批回调（BIZ_TYPE = NcrDisposition，
 * change rework-ncr-identification-disposition，spec CTQ 处置质量经理审批）。
 * 通过 → 执行评审流转段（含退货单/SCAR 自动带出），清 DISPO_PENDING_FLAG，日志记审批人；
 * 驳回 → 清 DISPO_PENDING_FLAG 与处置选择，留驳回意见日志，可改选重提。
 * 回调与审批同事务（ApprovalEngine 契约），异常整体回滚；幂等由 NcrService 保证。
 */
@Slf4j
@Component
public class NcrDispositionCallback implements ApprovalCallback {

    private final NcrService ncrService;
    private final ApprovalTaskDao taskDao;

    public NcrDispositionCallback(@Lazy NcrService ncrService,
                                  ApprovalTaskDao taskDao) {
        this.ncrService = ncrService;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("NcrDisposition");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        // 幂等（pending 且 CREATED）由 applyDispositionApproval 内部校验
        ncrService.applyDispositionApproval(instance.getBizId(), "审批通过");
        log.info("NCR {} disposition approved (biz {}), transition applied",
                instance.getBizId(), instance.getBizType());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        // 驳回意见取被拒节点的签署意见（ApprovalTask.opinion，参照 ConcessionCallback）
        ApprovalTask task = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1"));
        String reason = task != null && task.getOpinion() != null && !task.getOpinion().isBlank()
                ? task.getOpinion() : "质量经理驳回";
        ncrService.rejectDispositionApproval(instance.getBizId(), reason);
        log.info("NCR {} disposition rejected, back to CREATED for re-selection",
                instance.getBizId());
    }
}
