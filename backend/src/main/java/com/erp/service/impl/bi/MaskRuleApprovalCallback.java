package com.erp.service.impl.bi;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 脱敏规则审批回调（add-data-extract-console design D3，
 * spec bi-mask-rule-management「变更审批激活」）。
 * APPROVED → ENABLED + 留痕；REJECTED → DRAFT + 留痕 + 通知。
 *
 * ObjectProvider 懒取打断环：ApprovalServiceImpl → 本回调 → MaskRuleServiceImpl
 * → ApprovalEngine(=ApprovalServiceImpl) 构造期循环（Spring Boot 2.7 默认禁环）。
 */
@Slf4j
@Component
public class MaskRuleApprovalCallback implements ApprovalCallback {

    private final ObjectProvider<MaskRuleServiceImpl> serviceProvider;

    public MaskRuleApprovalCallback(
            ObjectProvider<MaskRuleServiceImpl> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("MaskRule");
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
