package com.erp.service.impl.mrp;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.mrp.ReportingService;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 超产确认审批回调（change add-reporting-management，spec shop-floor-reporting
 * 「超产分级确认」FR-4.5-5-4；approval-workflow 同事务回调）。
 * bizId = rptId；通过 → PROD_CONFIRM_FLAG=1（可提交）+ 推送申请人；
 * 驳回 → 报工单保持挂起 + 推送申请人（改量重提或放弃）。
 * 审批结果通知在业务回调内自推（5.6 D6 silent 纪律——底座不自动推）。
 */
@Slf4j
@Component
public class OverProdCallback implements ApprovalCallback {

    private final ReportingService reportingService;
    private final NoticeService noticeService;

    public OverProdCallback(ReportingService reportingService, NoticeService noticeService) {
        this.reportingService = reportingService;
        this.noticeService = noticeService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(ReportingServiceImpl.BIZ_OVERPROD);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        reportingService.approveOverProd(instance.getBizId(), true, null);
        noticeService.push(null, instance.getApplyBy(),
                "超产确认通过", "您的超产确认申请 " + instance.getApprNo()
                        + " 已通过，报工单可提交", "OVERPROD_RESULT", instance.getBizId());
        log.info("OverProd {} approved: {}", instance.getApprNo(), instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        reportingService.approveOverProd(instance.getBizId(), false, instance.getRemark());
        noticeService.push(null, instance.getApplyBy(),
                "超产确认驳回", "您的超产确认申请 " + instance.getApprNo()
                        + " 被驳回，请调整产出数量后重提", "OVERPROD_RESULT", instance.getBizId());
        log.info("OverProd {} rejected: {}", instance.getApprNo(), instance.getBizId());
    }
}
