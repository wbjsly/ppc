package com.erp.service.impl.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.sd.SdQuoteDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.sd.SdQuote;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 报价审批回调（BIZ_TYPE = Quote，spec sales-quote FR-4.3-1-6）。
 * 通过 → 发布（正式编号 + 有效期参数化）；驳回 → REJECTED 可改重提。回调与审批同事务。
 * 只注入 DAO + QuotePublisher（与既有回调同范式，不依赖 QuoteService，避免审批引擎构造循环）。
 */
@Slf4j
@Component
public class QuoteApprovalCallback implements ApprovalCallback {

    public static final String BIZ_TYPE = "Quote";

    private final SdQuoteDao quoteDao;
    private final ApprovalTaskDao taskDao;
    private final QuotePublisher publisher;

    public QuoteApprovalCallback(SdQuoteDao quoteDao, ApprovalTaskDao taskDao, QuotePublisher publisher) {
        this.quoteDao = quoteDao;
        this.taskDao = taskDao;
        this.publisher = publisher;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        SdQuote q = quoteDao.selectById(instance.getBizId());
        if (q == null) {
            log.warn("quote approved but record missing: {}", instance.getBizId());
            return;
        }
        publisher.publish(q.getId(), signerOf(instance.getId(), "PASSED"));
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        SdQuote q = quoteDao.selectById(instance.getBizId());
        if (q == null) {
            log.warn("quote rejected but record missing: {}", instance.getBizId());
            return;
        }
        publisher.reject(q.getId(), signerOf(instance.getId(), "REJECTED"),
                opinionOf(instance.getId()));
    }

    private String signerOf(String instanceId, String status) {
        ApprovalTask t = task(instanceId, status);
        return t == null || t.getSigner() == null ? "system" : t.getSigner();
    }

    private String opinionOf(String instanceId) {
        ApprovalTask t = task(instanceId, "REJECTED");
        return t == null ? null : t.getOpinion();
    }

    private ApprovalTask task(String instanceId, String status) {
        List<ApprovalTask> list = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, instanceId)
                .eq(ApprovalTask::getStatus, status));
        return list.isEmpty() ? null : list.get(0);
    }
}
