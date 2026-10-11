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
 * 报工冲销审批回调（change add-reporting-management，spec shop-floor-reporting
 * 「整单红字冲销」BR-4.5-25；approval-workflow 同事务回调）。
 * bizId = rptId；通过 → 五步反向（投料回加/红字镜像/原单 REVERSED）+ 推送申请人；
 * 驳回 → 报工单保持 CONFIRMED + 推送申请人。业务回调内自推（5.6 D6 silent 纪律）。
 */
@Slf4j
@Component
public class ReportReversalCallback implements ApprovalCallback {

    private final ReportingService reportingService;
    private final NoticeService noticeService;

    public ReportReversalCallback(ReportingService reportingService, NoticeService noticeService) {
        this.reportingService = reportingService;
        this.noticeService = noticeService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(ReportingServiceImpl.BIZ_REVERSAL);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        reportingService.applyReversal(instance.getBizId());
        noticeService.push(null, instance.getApplyBy(),
                "报工冲销通过", "您的报工冲销申请 " + instance.getApprNo()
                        + " 已通过，原报工单已红字冲销", "REVERSAL_RESULT", instance.getBizId());
        log.info("ReportReversal {} approved: {}", instance.getApprNo(), instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        noticeService.push(null, instance.getApplyBy(),
                "报工冲销驳回", "您的报工冲销申请 " + instance.getApprNo()
                        + " 被驳回，报工单保持已确认", "REVERSAL_RESULT", instance.getBizId());
        log.info("ReportReversal {} rejected: {}", instance.getApprNo(), instance.getBizId());
    }
}
