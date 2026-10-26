package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 折旧变动审核回调（add-asset-depreciation，BIZ_TYPE = AssetDepreciation，design D4）。
 * 通过 → 政策变更快照生效（下期读取不追溯）/ 减值出凭证 + 卡片 IMPAIRMENT 落值（同事务）；
 * 驳回 → 置 REJECTED 留原因。
 */
@Slf4j
@Component
public class AssetDepreciationCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamDepreciationService depreciationService;

    public AssetDepreciationCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamDepreciationService depreciationService) {
        this.depreciationService = depreciationService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetDepreciation");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        try {
            depreciationService.approveChange(instance.getBizId());
            log.info("折旧变动单 {} 审核通过 → 生效完成", instance.getBizId());
        } catch (Exception ex) {
            // 凭证/落值失败：回滚由事务保证；驳回留原因（不允许半提交）
            log.error("折旧变动单 {} 生效失败，置驳回：{}", instance.getBizId(), ex.getMessage());
            depreciationService.rejectChange(instance.getBizId(), "生效失败：" + ex.getMessage());
            throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        depreciationService.rejectChange(instance.getBizId(), "审核驳回");
        log.info("折旧变动单 {} 审核驳回", instance.getBizId());
    }
}
