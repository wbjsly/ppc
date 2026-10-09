package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.sd.SpecialPriceDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.sd.SpecialPrice;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 特殊价格审批回调（BIZ_TYPE = SpecialPrice，S-4.3-08 / FR-4.3-5-5）。
 * 通过 → APPROVED 生效（折扣引擎按客户+SKU+有效期检索放行并回写 SP_NO）；驳回 → REJECTED 可改重提。
 * 只注入 DAO（与既有回调同范式，避免审批引擎构造循环）。
 */
@Slf4j
@Component
public class SpecialPriceCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "SpecialPrice";

    private final SpecialPriceDao specialDao;
    private final ApprovalTaskDao taskDao;

    public SpecialPriceCallback(SpecialPriceDao specialDao, ApprovalTaskDao taskDao) {
        this.specialDao = specialDao;
        this.taskDao = taskDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        SpecialPrice sp = specialDao.selectById(instance.getBizId());
        if (sp == null) {
            log.warn("special price approved but record missing: {}", instance.getBizId());
            return;
        }
        if (SpecialPrice.ST_APPROVED.equals(sp.getStatus())) {
            return; // 幂等
        }
        if (!SpecialPrice.ST_PENDING.equals(sp.getStatus())) {
            throw new ServiceException(422, "特批单状态不可批准：" + sp.getStatus());
        }
        sp.setStatus(SpecialPrice.ST_APPROVED);
        sp.setDecideBy(signerOf(instance.getId(), "PASSED"));
        sp.setDecideAt(LocalDateTime.now());
        sp.setApprovalId(null);
        specialDao.updateById(sp);
        specialDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SpecialPrice>()
                .eq(SpecialPrice::getId, sp.getId())
                .set(SpecialPrice::getApprovalId, null));
        log.info("special price {} approved, {} → {}", sp.getSpNo(), sp.getOriginPrice(), sp.getSpecialPrice());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        SpecialPrice sp = specialDao.selectById(instance.getBizId());
        if (sp == null) {
            return;
        }
        if (SpecialPrice.ST_REJECTED.equals(sp.getStatus())) {
            return; // 幂等
        }
        ApprovalTask t = task(instance.getId(), "REJECTED");
        sp.setStatus(SpecialPrice.ST_REJECTED);
        sp.setDecideBy(t == null ? "system" : t.getSigner());
        sp.setDecideAt(LocalDateTime.now());
        sp.setOpinion(t == null ? null : t.getOpinion());
        specialDao.updateById(sp);
        specialDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SpecialPrice>()
                .eq(SpecialPrice::getId, sp.getId())
                .set(SpecialPrice::getApprovalId, null));
        log.info("special price {} rejected: {}", sp.getSpNo(), sp.getOpinion());
    }

    private String signerOf(String instanceId, String status) {
        ApprovalTask t = task(instanceId, status);
        return t == null || t.getSigner() == null ? "system" : t.getSigner();
    }

    private ApprovalTask task(String instanceId, String status) {
        List<ApprovalTask> list = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instanceId)
                .eq(ApprovalTask::getStatus, status));
        return list.isEmpty() ? null : list.get(0);
    }
}
