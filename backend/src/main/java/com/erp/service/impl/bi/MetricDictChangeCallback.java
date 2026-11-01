package com.erp.service.impl.bi;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 口径变更两段式治理回调（add-metric-dict-console design D2，
 * spec bi-metric-dictionary「变更审批与灰度状态机」）。
 * MetricDictChange：APPROVED → GRAYING（旧版仍生效）+ 反扫引用方 + 通知；
 *                  REJECTED → DRAFT 留痕。
 * MetricDictSwitch（C-4.10-08 双审）：APPROVED → 新版生效（IS_CURRENT 切换）+ 血缘刷新；
 *                  REJECTED → 保持 GRAYING 可重发切换。
 *
 * ObjectProvider 懒取打断环：ApprovalServiceImpl → 本回调 → MetricDictServiceImpl
 * → ApprovalEngine(=ApprovalServiceImpl) 构造期循环（Spring Boot 2.7 默认禁环）。
 */
@Slf4j
@Component
public class MetricDictChangeCallback implements ApprovalCallback {

    private final ObjectProvider<MetricDictServiceImpl> serviceProvider;

    public MetricDictChangeCallback(ObjectProvider<MetricDictServiceImpl> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("MetricDictChange", "MetricDictSwitch");
    }

    @Override
    public void onApproved(ApprovalInstance instance) {
        if ("MetricDictSwitch".equals(instance.getBizType())) {
            serviceProvider.getObject().applyApprovedChange(instance.getBizId());
        } else {
            serviceProvider.getObject().onGovernanceApproved(instance.getBizId());
        }
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        if ("MetricDictSwitch".equals(instance.getBizType())) {
            serviceProvider.getObject().onSwitchRejected(instance.getBizId());
        } else {
            serviceProvider.getObject().onGovernanceRejected(instance.getBizId());
        }
    }
}
