package com.erp.service.impl.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.TaxReturnAdjDao;
import com.erp.dao.fin.TaxReturnDao;
import com.erp.entity.fin.TaxReturn;
import com.erp.entity.fin.TaxReturnAdj;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 税务申报审批回调（BIZ_TYPE = TaxFiling / TaxFilingAdj，spec tax-filing）。
 * TaxFiling 通过 → APPROVED + 锁定（FR-4.14-5-5）；驳回 → REJECTED。
 * TaxFilingAdj 通过 → 按 afterJson 目标值应用到锁定期申报单（BR-4.14-31，
 * 重算应纳/留抵快照，adjustedFlag=1）；驳回 → 调整单 REJECTED（申报单不动）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxFilingApprovalCallback implements ApprovalCallback {

    private final TaxReturnDao returnDao;
    private final TaxReturnAdjDao adjDao;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(TaxFilingServiceImpl.BIZ_TYPE, TaxFilingServiceImpl.BIZ_TYPE_ADJ);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        if (TaxFilingServiceImpl.BIZ_TYPE.equals(instance.getBizType())) {
            onFilingApproved(instance);
        } else {
            onAdjApproved(instance);
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        if (TaxFilingServiceImpl.BIZ_TYPE.equals(instance.getBizType())) {
            TaxReturn r = returnDao.selectById(instance.getBizId());
            if (r == null || !TaxReturn.ST_PENDING_APPROVAL.equals(r.getStatus())) {
                return;
            }
            r.setStatus(TaxReturn.ST_REJECTED);
            r.setUpdateBy("approval");
            r.setUpdateDate(LocalDateTime.now());
            returnDao.updateById(r);
            log.info("tax return {} rejected", r.getReturnNo());
        } else {
            TaxReturnAdj adj = adjDao.selectById(instance.getBizId());
            if (adj == null || !TaxReturnAdj.ST_PENDING_APPROVAL.equals(adj.getStatus())) {
                return;
            }
            adj.setStatus(TaxReturnAdj.ST_REJECTED);
            adj.setUpdateBy("approval");
            adjDao.updateById(adj);
            log.info("tax return adj {} rejected", adj.getAdjNo());
        }
    }

    private void onFilingApproved(ApprovalInstance instance) {
        TaxReturn r = returnDao.selectById(instance.getBizId());
        if (r == null) {
            log.warn("tax filing approved but missing: {}", instance.getBizId());
            return;
        }
        if (!TaxReturn.ST_PENDING_APPROVAL.equals(r.getStatus())) {
            return; // 幂等
        }
        r.setStatus(TaxReturn.ST_APPROVED);
        r.setLockFlag("1"); // 锁定：修改走跨期调整（BR-4.14-31）
        r.setUpdateBy("approval");
        r.setUpdateDate(LocalDateTime.now());
        if (returnDao.updateById(r) == 0) {
            throw new ServiceException(409, "申报单状态更新冲突：" + r.getReturnNo());
        }
        log.info("tax return {} approved and locked", r.getReturnNo());
    }

    /** 跨期调整应用（BR-4.14-31：调整后快照写回 + 重算结转） */
    @SuppressWarnings("unchecked")
    private void onAdjApproved(ApprovalInstance instance) {
        TaxReturnAdj adj = adjDao.selectById(instance.getBizId());
        if (adj == null || !TaxReturnAdj.ST_PENDING_APPROVAL.equals(adj.getStatus())) {
            return; // 幂等
        }
        TaxReturn r = returnDao.selectById(adj.getReturnId());
        if (r == null) {
            throw new ServiceException(404, "调整关联申报单不存在：" + adj.getReturnId());
        }
        Map<String, Object> target;
        try {
            target = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(adj.getAfterJson(), Map.class);
        } catch (Exception e) {
            throw new ServiceException(422, "调整目标快照解析失败：" + adj.getAdjNo());
        }
        // 仅允许调整增值税汇总字段，重算 BR-32 结转
        if (target.get("outputTax") != null) {
            r.setOutputTax(new BigDecimal(String.valueOf(target.get("outputTax"))));
        }
        if (target.get("inputTax") != null) {
            r.setInputTax(new BigDecimal(String.valueOf(target.get("inputTax"))));
        }
        if (target.get("transferOut") != null) {
            r.setTransferOut(new BigDecimal(String.valueOf(target.get("transferOut"))));
        }
        BigDecimal computed = nvl(r.getOutputTax()).subtract(nvl(r.getInputTax()))
                .add(nvl(r.getTransferOut())).subtract(nvl(r.getPrevCredit()));
        r.setPayableTax(computed.signum() < 0 ? BigDecimal.ZERO : computed);
        r.setEndCredit(computed.signum() < 0 ? computed.abs() : BigDecimal.ZERO);
        r.setAdjustedFlag("1");
        r.setReconStatus(TaxReturn.RECON_BLOCKED); // 调整后须重新勾稽再提交
        r.setReconDiffTotal(BigDecimal.ZERO);
        r.setUpdateBy("approval");
        r.setUpdateDate(LocalDateTime.now());
        returnDao.updateById(r);
        adj.setStatus(TaxReturnAdj.ST_APPROVED);
        adj.setUpdateBy("approval");
        adjDao.updateById(adj);
        log.info("tax return adj {} applied: {} payable {}", adj.getAdjNo(), r.getReturnNo(),
                r.getPayableTax());
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
