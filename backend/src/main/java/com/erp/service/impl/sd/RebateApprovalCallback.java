package com.erp.service.impl.sd;

import com.erp.dao.sd.RebateSettlementDao;
import com.erp.entity.sd.RebateSettlement;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 返利结算审批回调（BIZ_TYPE = Rebate，spec sales-rebate FR-4.3-8-4）。
 * 通过 → APPROVED 可执行（冲抵/兑现）；驳回 → 退回 REJECTED 可改后重新提交。
 * 仅注入 DAO（不依赖 RebateService，避免与审批引擎循环依赖）。
 */
@Slf4j
@Component
public class RebateApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "Rebate";

    private final RebateSettlementDao settleDao;

    public RebateApprovalCallback(RebateSettlementDao settleDao) {
        this.settleDao = settleDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        RebateSettlement s = settleDao.selectById(instance.getBizId());
        if (s == null) {
            log.warn("rebate approved but record missing: {}", instance.getBizId());
            return;
        }
        s.setStatus(RebateSettlement.ST_APPROVED);
        settleDao.updateById(s);
        log.info("rebate {} approved", s.getSettleNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        RebateSettlement s = settleDao.selectById(instance.getBizId());
        if (s == null) {
            log.warn("rebate rejected but record missing: {}", instance.getBizId());
            return;
        }
        // 退回发起人调整方案后重新提交（FR-4.3-8-4 驳回退回）
        s.setStatus(RebateSettlement.ST_REJECTED);
        settleDao.updateById(s);
        log.info("rebate {} rejected, back to originator", s.getSettleNo());
    }
}
