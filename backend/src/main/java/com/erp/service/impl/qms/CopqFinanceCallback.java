package com.erp.service.impl.qms;

import com.erp.common.ServiceException;
import com.erp.dao.qms.CopqEntryDao;
import com.erp.entity.qms.CopqEntry;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * COPQ 财务确认回调（BIZ_TYPE = CopqFinance，tasks 9.7，spec quality-cost）。
 * 通过 → CONFIRMED + 确认人/时间（BR-4.12-40 财务确认后方可进正式报表）；驳回 → 回草稿可重提。
 * 财务域未建（D2 偏差）：节点由 ADMIN 代签。
 */
@Slf4j
@Component
public class CopqFinanceCallback implements ApprovalCallback {

    private final CopqEntryDao entryDao;

    public CopqFinanceCallback(CopqEntryDao entryDao) {
        this.entryDao = entryDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("CopqFinance");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        CopqEntry e = entryDao.selectById(instance.getBizId());
        if (e == null) {
            log.warn("CopqFinance approved but entry missing: {}", instance.getBizId());
            return;
        }
        if ("CONFIRMED".equals(e.getStatus())) {
            return; // 幂等
        }
        if ("ANOMALY_HOLD".equals(e.getStatus())) {
            throw new ServiceException(422, "金额异常待核，不可确认（BR-4.12-39）");
        }
        e.setStatus("CONFIRMED");
        e.setApprovalId(instance.getId());
        e.setConfirmBy(SecurityUtils.getCurrentUserId());
        e.setConfirmDate(LocalDateTime.now());
        if (entryDao.updateById(e) == 0) {
            throw new ServiceException(422, "成本单状态更新冲突");
        }
        log.info("COPQ {} confirmed (category={}, amount={})", e.getEntryNo(), e.getCategory(), e.getAmount());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        CopqEntry e = entryDao.selectById(instance.getBizId());
        if (e == null || "CONFIRMED".equals(e.getStatus())) {
            return;
        }
        e.setStatus("PENDING_FINANCE");
        e.setApprovalId(instance.getId());
        entryDao.updateById(e);
        log.info("COPQ {} finance rejected → back to draft", e.getEntryNo());
    }
}
