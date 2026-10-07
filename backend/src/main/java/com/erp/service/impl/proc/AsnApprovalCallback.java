package com.erp.service.impl.proc;

import com.erp.common.ServiceException;
import com.erp.dao.proc.AsnDao;
import com.erp.entity.proc.Asn;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * ASN 超收审批回调（spec asn-collaboration，design D6，BIZ_TYPE=AsnOverTolerance）。
 * 通过 → OVER_STATUS=RELEASED（超收部分可收货）；驳回 → REJECTED（超收作废，可修正重报）。
 * 缺回调 = 审批通过无业务动作（ScarApprovalCallback 前车之鉴）→ 必须注册。
 */
@Slf4j
@Component
public class AsnApprovalCallback implements ApprovalCallback {

    private final AsnDao asnDao;

    public AsnApprovalCallback(AsnDao asnDao) {
        this.asnDao = asnDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(AsnServiceImpl.BIZ_ASN_OVER);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        Asn a = asnDao.selectById(instance.getBizId());
        if (a == null) {
            log.warn("AsnOverTolerance approved but ASN missing: {}", instance.getBizId());
            return;
        }
        if (Asn.OVER_RELEASED.equals(a.getOverStatus())) {
            return; // 幂等
        }
        if (!Asn.OVER_PENDING.equals(a.getOverStatus())) {
            throw new ServiceException(422, "ASN 超收状态不可放行：" + a.getOverStatus());
        }
        a.setOverStatus(Asn.OVER_RELEASED);
        a.setApprovalId(instance.getId());
        if (asnDao.updateById(a) == 0) {
            throw new ServiceException(422, "ASN 超收放行更新冲突");
        }
        log.info("ASN {} 超收放行（{}）", a.getAsnNo(), a.getOverToleranceQty());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        Asn a = asnDao.selectById(instance.getBizId());
        if (a == null || !Asn.OVER_PENDING.equals(a.getOverStatus())) {
            return;
        }
        a.setOverStatus(Asn.OVER_REJECTED);
        a.setApprovalId(instance.getId());
        asnDao.updateById(a);
        log.info("ASN {} 超收驳回（超收部分作废，可修正后重报）", a.getAsnNo());
    }
}
