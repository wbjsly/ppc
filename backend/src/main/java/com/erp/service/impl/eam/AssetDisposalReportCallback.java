package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 处置结案报告归档回调（add-asset-disposal，BIZ_TYPE = AssetDisposalReport）。
 * 通过 → 处置单 CLOSED 归档；驳回 → 保持 EXECUTED/RECEIVABLE 可重新提交。
 */
@Slf4j
@Component
public class AssetDisposalReportCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamDisposalService disposalService;

    public AssetDisposalReportCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamDisposalService disposalService) {
        this.disposalService = disposalService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetDisposalReport");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        disposalService.onReportClosed(instance.getBizId());
        log.info("处置单 {} 结案报告归档 CLOSED", instance.getBizId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        // 驳回不改处置单状态（保持可执行/可收款态，报告可重提）
        log.info("处置单 {} 结案报告驳回（保持原状态可重提）", instance.getBizId());
    }
}
