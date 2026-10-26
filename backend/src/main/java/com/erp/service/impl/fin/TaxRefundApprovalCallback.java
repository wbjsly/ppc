package com.erp.service.impl.fin;

import com.erp.dao.fin.TaxRefundApplyDao;
import com.erp.entity.fin.TaxRefundApply;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 留抵退税审批回调（BIZ_TYPE = TaxRefundApply，spec tax-filing）。
 * L2 通过 → APPROVED（核准额=允许退还，可登记税务机关受理/到账）；驳回 → REJECTED 留痕。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxRefundApprovalCallback implements ApprovalCallback {

    private final TaxRefundApplyDao applyDao;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(TaxRefundServiceImpl.BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        TaxRefundApply a = applyDao.selectById(instance.getBizId());
        if (a == null) {
            log.warn("LDTI approved but missing: {}", instance.getBizId());
            return;
        }
        if (!TaxRefundApply.ST_PENDING_APPROVAL.equals(a.getStatus())) {
            return; // 幂等
        }
        a.setStatus(TaxRefundApply.ST_APPROVED);
        a.setApprovedAmount(a.getAllowedRefund()); // 内部核准额 = 公式允许退还
        a.setUpdateBy("approval");
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("LDTI apply {} approved: {}", a.getApplyNo(), a.getAllowedRefund());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        TaxRefundApply a = applyDao.selectById(instance.getBizId());
        if (a == null || !TaxRefundApply.ST_PENDING_APPROVAL.equals(a.getStatus())) {
            return;
        }
        a.setStatus(TaxRefundApply.ST_REJECTED);
        a.setUpdateBy("approval");
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("LDTI apply {} rejected", a.getApplyNo());
    }
}
