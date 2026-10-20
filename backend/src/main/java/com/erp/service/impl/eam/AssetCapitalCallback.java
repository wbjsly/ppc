package com.erp.service.impl.eam;

import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 转资审核回调（add-asset-capitalization，BIZ_TYPE = AssetCapital，design D4）。
 * 通过 → 一物一卡建卡 + 转资凭证 + 置 COMPLETED（同事务，失败整体回滚）；
 * 驳回 → 回 DRAFT 留原因可改重提。
 */
@Slf4j
@Component
public class AssetCapitalCallback implements ApprovalCallback {

    private final com.erp.service.eam.EamCapitalizationService capService;

    public AssetCapitalCallback(
            @org.springframework.context.annotation.Lazy
            com.erp.service.eam.EamCapitalizationService capService) {
        this.capService = capService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("AssetCapital");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        try {
            capService.completeFromApproval(instance.getBizId());
            log.info("转资单 {} 审核通过 → 建卡出凭证完成", instance.getBizId());
        } catch (Exception ex) {
            // 凭证/建卡失败：回滚由事务保证；回草稿 + 告警（D4 不允许半提交）
            log.error("转资单 {} 建卡出凭证失败，回草稿：{}", instance.getBizId(), ex.getMessage());
            capService.rejectToDraft(instance.getBizId(), "建卡/凭证生成失败：" + ex.getMessage());
            throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        capService.rejectToDraft(instance.getBizId(), "审核驳回");
        log.info("转资单 {} 审核驳回 → 回草稿", instance.getBizId());
    }
}
