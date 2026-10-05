package com.erp.service.impl.qms;

import com.erp.dao.qms.ExemptDao;
import com.erp.entity.qms.Exempt;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 免检审批回调（BIZ_TYPE = Exempt，BR-4.12-12）：
 * 通过 → STATUS=ACTIVE 并记录生效时间；驳回 → STATUS=DRAFT-equivalent（REJECTED，可重新申请）。
 * 未批准前检验批 MUST NOT 跳过检验（生成侧只认 ACTIVE）。
 */
@Slf4j
@Component
public class ExemptCallback implements ApprovalCallback {

    private final ExemptDao exemptDao;

    public ExemptCallback(ExemptDao exemptDao) {
        this.exemptDao = exemptDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("Exempt");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        Exempt e = exemptDao.selectById(instance.getBizId());
        if (e == null || "ACTIVE".equals(e.getStatus())) {
            return;
        }
        e.setStatus("ACTIVE");
        e.setEnabledBy(instance.getApplyBy());
        e.setEnabledDate(LocalDateTime.now());
        e.setApprovalId(instance.getId());
        exemptDao.updateById(e);
        log.info("exempt activated: {} / {}", e.getMaterialCode(), e.getSupplierId());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        Exempt e = exemptDao.selectById(instance.getBizId());
        if (e == null || "ACTIVE".equals(e.getStatus())) {
            return;
        }
        e.setStatus("REJECTED");
        e.setApprovalId(instance.getId());
        exemptDao.updateById(e);
        log.info("exempt rejected: {}", e.getMaterialCode());
    }
}
