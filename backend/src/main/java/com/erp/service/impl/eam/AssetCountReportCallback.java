package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 盘点报告归档审批回调（add-asset-count，BIZ_TYPE = AssetCountReport，design D2）。
 * 通过 → 盘点单置 CLOSED（BR-26 覆盖率已在提交时卡控）；驳回 → 回 DIFF_REVIEW 留因。
 */
@Slf4j
@Component
public class AssetCountReportCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamCountService countService;

    public AssetCountReportCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamCountService countService) {
        this.countService = countService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetCountReport");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        countService.onReportApproved(instance.getBizId());
        log.info("盘点单 {} 报告归档审批通过 → 结案", instance.getBizId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        countService.onReportRejected(instance.getBizId(), "报告审批驳回");
        log.info("盘点单 {} 报告归档审批驳回 → 回差异复核", instance.getBizId());
    }
}
