package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 维护计划审批回调（add-asset-maintenance，BIZ_TYPE = MaintPlan，design D2）。
 * 通过 → 计划置 APPROVED（生效可生成工单）；驳回 → 置 REJECTED 可改重提。
 */
@Slf4j
@Component
public class MaintPlanCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamMaintPlanService planService;

    public MaintPlanCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamMaintPlanService planService) {
        this.planService = planService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("MaintPlan");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        planService.approveFromApproval(instance.getBizId());
        log.info("维护计划 {} 审批通过", instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        planService.rejectFromApproval(instance.getBizId(), "设备主管驳回");
        log.info("维护计划 {} 审批驳回", instance.getBizId());
    }
}
