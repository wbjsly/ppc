package com.erp.service.impl.bi;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 报表发布审批回调（add-report-template-console design D4，
 * spec report-template-management「三方发布审批与二次校验」）。
 * APPROVED → 发布前二次口径校验（BR-4.10-11，失败抛 422 中止保持前一版）
 * → 快照 + PUBLISHED；REJECTED → REJECTED。
 * 回调与审批同事务——校验失败异常 → 审批与业务一并回滚。
 *
 * ObjectProvider 懒取打断环：ApprovalServiceImpl → 本回调 → ServiceImpl →
 * ApprovalEngine(=ApprovalServiceImpl) 构造期循环（Spring Boot 2.7 默认禁环）。
 */
@Slf4j
@Component
public class ReportTemplateApprovalCallback implements ApprovalCallback {

    private final ObjectProvider<ReportTemplateServiceImpl> serviceProvider;

    public ReportTemplateApprovalCallback(
            ObjectProvider<ReportTemplateServiceImpl> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("ReportTemplate");
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        serviceProvider.getObject().activateOnApproved(instance.getBizId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        serviceProvider.getObject().rejectOnCallback(instance.getBizId());
    }
}
