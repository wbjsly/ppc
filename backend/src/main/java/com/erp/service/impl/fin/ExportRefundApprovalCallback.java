package com.erp.service.impl.fin;

import com.erp.dao.fin.ExportRefundApplyDao;
import com.erp.entity.fin.ExportRefundApply;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 出口退税申报审批回调（BIZ_TYPE = ExportRefundApply，spec export-tax-refund）。
 * L2 通过 → SUBMITTED（视为已提交税务机关，进入受理/审核状态跟踪）；驳回 → REJECTED 留痕。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportRefundApprovalCallback implements ApprovalCallback {

    private final ExportRefundApplyDao applyDao;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(ExportTaxRefundServiceImpl.BIZ_TYPE_APPLY);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        ExportRefundApply a = applyDao.selectById(instance.getBizId());
        if (a == null) {
            log.warn("export refund approved but missing: {}", instance.getBizId());
            return;
        }
        if (!ExportRefundApply.ST_DRAFT.equals(a.getStatus())
                && !ExportRefundApply.ST_RETURNED.equals(a.getStatus())) {
            return; // 幂等
        }
        a.setStatus(ExportRefundApply.ST_SUBMITTED);
        a.setUpdateBy("approval");
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("export refund {} approved → SUBMITTED", a.getApplyNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        ExportRefundApply a = applyDao.selectById(instance.getBizId());
        if (a == null) {
            return;
        }
        if (!ExportRefundApply.ST_DRAFT.equals(a.getStatus())
                && !ExportRefundApply.ST_RETURNED.equals(a.getStatus())) {
            return;
        }
        a.setStatus(ExportRefundApply.ST_REJECTED);
        a.setUpdateBy("approval");
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("export refund {} rejected", a.getApplyNo());
    }
}
