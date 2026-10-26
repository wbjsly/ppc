package com.erp.service.impl.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.CashDocDao;
import com.erp.entity.fin.CashDoc;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.dao.system.ApprovalTaskDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.fin.CashWriteoffService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 跨期核销审批回调（BIZ_TYPE = CashWriteoffCross，spec payment-collection C-4.6-02）。
 * 通过 → 执行挂起核销回写（applyApprovedSettle）；驳回 → 删除挂起核销行、未清额不变。
 */
@Slf4j
@Component
public class CashWriteoffApprovalCallback implements ApprovalCallback {

    private final CashDocDao docDao;
    private final ApprovalTaskDao taskDao;
    private final CashWriteoffService writeoffService;

    public CashWriteoffApprovalCallback(CashDocDao docDao, ApprovalTaskDao taskDao,
                                        CashWriteoffService writeoffService) {
        this.docDao = docDao;
        this.taskDao = taskDao;
        this.writeoffService = writeoffService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(CashWriteoffServiceImpl.BIZ_CROSS);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        CashDoc doc = docDao.selectById(instance.getBizId());
        if (doc == null) {
            log.warn("cross writeoff approved but doc missing: {}", instance.getBizId());
            return;
        }
        if (CashDoc.WO_DONE.equals(doc.getWriteoffState())) {
            return; // 幂等
        }
        if (!CashDoc.WO_PENDING.equals(doc.getWriteoffState())) {
            throw new ServiceException(422, "跨期核销状态不可批准：" + doc.getWriteoffState());
        }
        writeoffService.applyApprovedSettle(doc.getId());
        log.info("cash doc {} cross writeoff approved & applied", doc.getDocNo());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        CashDoc doc = docDao.selectById(instance.getBizId());
        if (doc == null || CashDoc.WO_DONE.equals(doc.getWriteoffState())) {
            return;
        }
        ApprovalTask t = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instance.getId())
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1"));
        String reason = t != null && t.getOpinion() != null ? t.getOpinion() : "跨期核销审批驳回";
        ((CashWriteoffServiceImpl) writeoffService).cancelPendingSettle(doc.getId(), reason);
        log.info("cash doc {} cross writeoff rejected: {}", doc.getDocNo(), reason);
    }
}
