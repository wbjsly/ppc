package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.mrp.TrialCostDao;
import com.erp.entity.mrp.TrialCost;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 试产转库存冲回财务确认回调（change add-trial-production-management，spec trial-execution
 * 「放行转可用与计价冲回」/ design D8：TrialCostReverse 单节点 FINANCE_MGR）。
 * bizId = 试产成本行 ID；通过 → FIN_STATUS=CONFIRMED + 签署人时间留痕（计入正式汇总）；
 * 驳回 → 保持 PENDING_FINANCE（不入汇总，可重新送签）。
 */
@Slf4j
@Component
public class TrialCostReverseCallback implements ApprovalCallback {

    private final TrialCostDao trialCostDao;

    public TrialCostReverseCallback(TrialCostDao trialCostDao) {
        this.trialCostDao = trialCostDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(TrialStockListener.BIZ_COST_REVERSE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        TrialCost row = trialCostDao.selectById(instance.getBizId());
        if (row == null) {
            log.warn("TrialCostReverse approved but cost row missing: {}", instance.getBizId());
            return;
        }
        if (TrialCost.FIN_CONFIRMED.equals(row.getFinStatus())) {
            return; // 幂等
        }
        row.setFinStatus(TrialCost.FIN_CONFIRMED);
        row.setApprovalId(instance.getId());
        // 签署人 = 回调当下用户（审批通过事务内 = 节点签署人，CopqFinanceCallback 同款）
        row.setConfirmBy(com.erp.util.SecurityUtils.getCurrentUserId());
        row.setConfirmAt(LocalDateTime.now());
        if (trialCostDao.updateById(row) == 0) {
            throw new com.erp.common.ServiceException(422, "冲回分录状态更新冲突");
        }
        log.info("试产冲回分录 {} 财务确认（金额 {}）", row.getEntryNo(), row.getAmount());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        TrialCost row = trialCostDao.selectById(instance.getBizId());
        if (row == null || TrialCost.FIN_CONFIRMED.equals(row.getFinStatus())) {
            return;
        }
        // 驳回保持 PENDING_FINANCE（未确认不入正式汇总），留审批实例号可追溯
        row.setApprovalId(instance.getId());
        trialCostDao.updateById(row);
        log.info("试产冲回分录 {} 财务驳回（保持待确认）", row.getEntryNo());
    }
}
