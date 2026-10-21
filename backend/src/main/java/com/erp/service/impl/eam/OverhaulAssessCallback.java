package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 大修/更换评估审批回调（add-asset-maintenance，BIZ_TYPE = AssetOverhaul，design D6/BR-19）。
 * 通过/驳回均视为评估结论归档 → 解冻同类故障工单创建。
 */
@Slf4j
@Component
public class OverhaulAssessCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamWorkOrderService woService;

    public OverhaulAssessCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamWorkOrderService woService) {
        this.woService = woService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetOverhaul");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        woService.completeOverhaulAssess(instance.getBizId(), "APPROVED");
        log.info("大修评估 {} 通过归档，解冻同类工单", instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        woService.completeOverhaulAssess(instance.getBizId(), "REJECTED");
        log.info("大修评估 {} 驳回归档，解冻同类工单", instance.getBizId());
    }
}
