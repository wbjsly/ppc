package com.erp.service.impl.sd;

import com.erp.dao.sd.SdReturnDao;
import com.erp.entity.sd.SdReturn;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 销售退货审批回调（BIZ_TYPE = SdReturn，spec sales-return 12.4）。
 * 通过 → APPROVED 可执行退款/换货；驳回 → REJECTED 退回发起人（可改后重判重提）。
 * 仅注入 DAO（不依赖 ReturnService，避免与审批引擎循环依赖）。
 */
@Slf4j
@Component
public class SdReturnApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "SdReturn";

    private final SdReturnDao returnDao;

    public SdReturnApprovalCallback(SdReturnDao returnDao) {
        this.returnDao = returnDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        SdReturn ret = returnDao.selectById(instance.getBizId());
        if (ret == null) {
            log.warn("return approved but record missing: {}", instance.getBizId());
            return;
        }
        ret.setStatus(SdReturn.ST_APPROVED);
        returnDao.updateById(ret);
        log.info("return {} approved", ret.getReturnNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        SdReturn ret = returnDao.selectById(instance.getBizId());
        if (ret == null) {
            log.warn("return rejected but record missing: {}", instance.getBizId());
            return;
        }
        ret.setStatus(SdReturn.ST_REJECTED);
        returnDao.updateById(ret);
        log.info("return {} rejected, back to originator", ret.getReturnNo());
    }
}
