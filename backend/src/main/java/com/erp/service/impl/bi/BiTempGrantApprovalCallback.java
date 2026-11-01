package com.erp.service.impl.bi;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.bi.TempGrantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 临时授权审批回调（add-perm-control-console，spec bi-temp-grant）。
 * APPROVED → ACTIVE（钳制 ≤24h）；REJECTED → REJECTED。
 * ObjectProvider 懒取打断环（同 MaskRuleApprovalCallback 范式）。
 */
@Slf4j
@Component
public class BiTempGrantApprovalCallback implements ApprovalCallback {

    private final ObjectProvider<TempGrantServiceImpl> serviceProvider;

    public BiTempGrantApprovalCallback(
            ObjectProvider<TempGrantServiceImpl> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("BiTempGrant");
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
