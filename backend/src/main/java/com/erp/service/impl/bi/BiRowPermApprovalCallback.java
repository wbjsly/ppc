package com.erp.service.impl.bi;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.bi.RowPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 行级权限审批回调（add-perm-control-console，spec bi-row-permission）。
 * APPROVED → ENABLED + 版本快照；REJECTED → DRAFT + 通知。
 * ObjectProvider 懒取打断环（同 MaskRuleApprovalCallback 范式，Spring Boot 2.7 禁构造环）。
 */
@Slf4j
@Component
public class BiRowPermApprovalCallback implements ApprovalCallback {

    private final ObjectProvider<RowPermissionService> serviceProvider;

    public BiRowPermApprovalCallback(
            ObjectProvider<RowPermissionService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("BiRowPerm");
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
