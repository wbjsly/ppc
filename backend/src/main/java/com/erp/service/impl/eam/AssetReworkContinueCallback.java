package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 技改特批续提审批回调（add-asset-rework，BIZ_TYPE = AssetReworkContinue，BR-39）。
 * 通过 → 写 CONTINUE_DEPR_FLAG='1'（折旧引擎第三分支放行）；驳回 → 维持停提。
 */
@Slf4j
@Component
public class AssetReworkContinueCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamReworkService reworkService;

    public AssetReworkContinueCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamReworkService reworkService) {
        this.reworkService = reworkService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetReworkContinue");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        reworkService.onContinueApproved(instance.getBizId());
        log.info("技改单 {} 特批续提通过（期间继续计提）", instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        reworkService.onContinueRejected(instance.getBizId(), "特批续提驳回");
        log.info("技改单 {} 特批续提驳回（维持停提）", instance.getBizId());
    }
}
