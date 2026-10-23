package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 租赁重新计量审核回调（add-asset-transfer-lease，BIZ_TYPE = AssetLeaseRemeasure，design D6）。
 * 通过 → 执行剩余行重算 + 快照归档 + ROU 原值差额调整凭证（同事务）；驳回 → 仅留痕（合同态不动）。
 */
@Slf4j
@Component
public class AssetLeaseRemeasureCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamLeaseService leaseService;

    public AssetLeaseRemeasureCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamLeaseService leaseService) {
        this.leaseService = leaseService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetLeaseRemeasure");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        leaseService.executeRemeasure(instance.getBizId());
        log.info("租赁合同 {} 重新计量审核通过 → 重算完成", instance.getBizId());
    }

    @Override
    public void onRejected(ApprovalInstance instance) {
        log.info("租赁合同 {} 重新计量审核驳回（待执行参数保留在 CHANGE_JSON）", instance.getBizId());
    }
}
