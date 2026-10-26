package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.RedFlushApplyDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.entity.fin.RedFlushApply;
import com.erp.entity.fin.SalesInvoice;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 红冲审批回调（BIZ_TYPE = RedFlush，spec red-invoice-void）。
 * onApproved：①跨期评估校验（BR-4.14-27：审批意见须含「更正申报」评估结论，缺失 → 422 整单回滚）；
 * ②红字信息单生成（BR-4.14-25：购方已认证抵扣（系内代理：该客户存在 CONFIRMED 发票）
 * → 填开方=BUYER 并提示通知购方；否则 SELLER），置 INFO_ISSUED。
 * onRejected：置 REJECTED 留痕。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedFlushApprovalCallback implements ApprovalCallback {

    private final RedFlushApplyDao applyDao;
    private final SalesInvoiceDao invoiceDao;
    private final com.erp.dao.system.ApprovalTaskDao approvalTaskDao;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(RedFlushServiceImpl.BIZ_TYPE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        RedFlushApply a = applyDao.selectById(instance.getBizId());
        if (a == null) {
            log.warn("red flush approved but missing: {}", instance.getBizId());
            return;
        }
        if (!RedFlushApply.ST_DRAFT.equals(a.getStatus())) {
            return; // 幂等
        }
        // ① 跨期评估留痕（BR-4.14-27 / C-4.14-05）：意见须含「更正申报」评估结论
        if ("1".equals(a.getCrossPeriodFlag())) {
            String opinion = lastOpinion(instance.getId());
            if (opinion == null || !opinion.contains("更正申报")) {
                throw new ServiceException(422,
                        "跨期红冲审批意见须包含「是否需更正申报」评估结论（BR-4.14-27），请驳回后补充意见重新审批");
            }
            a.setCrossPeriodNote(opinion);
        }
        // ② 红字信息单：填开方按购方认证抵扣状态判定（系内代理：客户存在 CONFIRMED 发票）
        long confirmed = invoiceDao.selectCount(new LambdaQueryWrapper<SalesInvoice>()
                .eq(SalesInvoice::getCustomerId, a.getCustomerId())
                .eq(SalesInvoice::getStatus, SalesInvoice.ST_CONFIRMED)
                .eq(SalesInvoice::getDelFlag, "0"));
        a.setInfoSheetFiller(confirmed > 0 ? RedFlushApply.FILLER_BUYER
                : RedFlushApply.FILLER_SELLER);
        a.setStatus(RedFlushApply.ST_INFO_ISSUED);
        a.setUpdateBy("approval");
        a.setUpdateDate(LocalDateTime.now());
        if (applyDao.updateById(a) == 0) {
            throw new ServiceException(409, "红冲申请状态更新冲突：" + a.getApplyNo());
        }
        log.info("red flush {} approved → info sheet (filler={}) buyerConfirmed={}",
                a.getApplyNo(), a.getInfoSheetFiller(), confirmed > 0);
        if (RedFlushApply.FILLER_BUYER.equals(a.getInfoSheetFiller())) {
            log.info("[NOTICE] 红冲 {} 原票购方已认证抵扣：请通知购方在综合服务平台填开信息表并跟进编号回传",
                    a.getApplyNo());
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        RedFlushApply a = applyDao.selectById(instance.getBizId());
        if (a == null || !RedFlushApply.ST_DRAFT.equals(a.getStatus())) {
            return;
        }
        a.setStatus(RedFlushApply.ST_REJECTED);
        a.setUpdateBy("approval");
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("red flush {} rejected (no info sheet)", a.getApplyNo());
    }

    private String lastOpinion(String apprId) {
        List<ApprovalTask> tasks = approvalTaskDao.selectList(
                new LambdaQueryWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getApprId, apprId)
                        .eq(ApprovalTask::getStatus, "PASSED")
                        .orderByDesc(ApprovalTask::getSeq));
        for (ApprovalTask t : tasks) {
            if (t.getOpinion() != null && !t.getOpinion().trim().isEmpty()) {
                return t.getOpinion();
            }
        }
        return null;
    }
}
