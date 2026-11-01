package com.erp.service.impl.bi;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 报表 SLA 报告审批回调（add-bi-sla-report design D3）。
 * APPROVED → 归档版本锁定（ARCHIVED）；REJECTED → 回 DRAFT。
 * ObjectProvider 懒取打断环（沿 14.1 ReportTemplateApprovalCallback 先例）。
 */
@Slf4j
@Component
public class BiSlaReportApprovalCallback implements ApprovalCallback {

    private final ObjectProvider<BiSlaReportServiceImpl> serviceProvider;

    public BiSlaReportApprovalCallback(
            ObjectProvider<BiSlaReportServiceImpl> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("BiSlaReport");
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        serviceProvider.getObject().activateOnApproved(instance.getBizId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        serviceProvider.getObject().rejectOnCallback(instance.getBizId(),
                instance.getRemark() == null ? "审核驳回" : instance.getRemark());
    }
}
