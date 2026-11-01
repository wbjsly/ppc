package com.erp.service.impl.bi;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.bi.ExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 超量导出审批回调（add-export-console，spec bi-export-overlimit-approval）。
 * APPROVED → 任务审批通过 + 频次告警；REJECTED → 任务 FAILED + 通知。
 * ObjectProvider 懒取打断环（同 MaskRuleApprovalCallback 范式）。
 */
@Slf4j
@Component
public class ExportOverLimitCallback implements ApprovalCallback {

    private final ObjectProvider<ExportService> serviceProvider;

    public ExportOverLimitCallback(ObjectProvider<ExportService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("BiExportOverLimit");
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        serviceProvider.getObject().onOverlimitApproved(instance.getBizId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        serviceProvider.getObject().onOverlimitRejected(instance.getBizId());
    }
}
