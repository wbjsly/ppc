package com.erp.service.impl.qms;

import com.erp.common.ServiceException;
import com.erp.dao.qms.ScarDao;
import com.erp.dao.qms.ScarDeductionDao;
import com.erp.entity.qms.Scar;
import com.erp.entity.qms.ScarDeduction;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.service.approval.ApprovalCallback;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * SCAR 审批回调（BIZ_TYPE = ScarMajor / ScarFinance，spec supplier-quality-claim）。
 *  - ScarMajor：重大 SCAR 质量经理批准 → 发出（SENT + 5 工作日回复时限）
 *  - ScarFinance：扣款财务确认（ADMIN 代，D3 偏差）→ CONFIRMED + 确认人留痕
 * 与审批同事务；驳回 → 单据回草稿可重提。
 */
@Slf4j
@Component
public class ScarApprovalCallback implements ApprovalCallback {

    private final ScarDao scarDao;
    private final ScarDeductionDao deductionDao;
    private final ApprovalTaskDao taskDao;
    /** COPQ 归集（rework-copq-analysis 2.1：扣款确认 → 外部失败，降级不阻断确认） */
    private final com.erp.service.qms.CopqService copqService;

    public ScarApprovalCallback(ScarDao scarDao, ScarDeductionDao deductionDao,
                                ApprovalTaskDao taskDao,
                                @org.springframework.context.annotation.Lazy
                                com.erp.service.qms.CopqService copqService) {
        this.scarDao = scarDao;
        this.deductionDao = deductionDao;
        this.taskDao = taskDao;
        this.copqService = copqService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of("ScarMajor", "ScarFinance");
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        if ("ScarMajor".equals(instance.getBizType())) {
            Scar s = scarDao.selectById(instance.getBizId());
            if (s == null) {
                log.warn("ScarMajor approved but SCAR missing: {}", instance.getBizId());
                return;
            }
            if ("SENT".equals(s.getStatus())) {
                return; // 幂等
            }
            s.setStatus("SENT");
            s.setSentDate(LocalDateTime.now());
            s.setApprovalId(instance.getId());
            s.setReplyDueDate(addWorkdays(LocalDate.now(), 5));
            if (scarDao.updateById(s) == 0) {
                throw new ServiceException(422, "SCAR 状态更新冲突");
            }
            log.info("major SCAR {} approved → sent, reply due {}", s.getScarNo(), s.getReplyDueDate());
            return;
        }
        // ScarFinance
        ScarDeduction d = deductionDao.selectById(instance.getBizId());
        if (d == null) {
            log.warn("ScarFinance approved but deduction missing: {}", instance.getBizId());
            return;
        }
        if ("CONFIRMED".equals(d.getStatus())) {
            return; // 幂等
        }
        d.setStatus("CONFIRMED");
        d.setApprovalId(instance.getId());
        d.setConfirmBy(SecurityUtils.getCurrentUserId());
        d.setConfirmDate(LocalDateTime.now());
        if (deductionDao.updateById(d) == 0) {
            throw new ServiceException(422, "扣款单状态更新冲突");
        }
        log.info("deduction {} finance confirmed: {}", d.getDeductNo(), d.getAmount());
        // ---- COPQ 归集（rework-copq-analysis 2.1，FR-4.12-6-2 ③）：扣款确认 = 实际外部失败 ----
        // 幂等键 SCAR+扣款单 ID（record 既有短路）；降级不阻断扣款确认主流程
        try {
            copqService.record(java.util.Map.of(
                    "category", "EXTERNAL_FAILURE",
                    "sourceType", "SCAR",
                    "sourceId", d.getId(),
                    "sourceNo", d.getDeductNo() == null ? "" : d.getDeductNo(),
                    "scarId", d.getScarId() == null ? "" : d.getScarId(),
                    "amount", d.getAmount(),
                    "supplierId", d.getSupplierId() == null ? "" : d.getSupplierId(),
                    "supplierName", d.getSupplierName() == null ? "" : d.getSupplierName(),
                    "remark", "SCAR 扣款财务确认归集（" + d.getDeductNo() + "）"));
        } catch (Exception ex) {
            log.warn("COPQ record failed for deduction {}（降级不阻断确认，可手工补录）：{}",
                    d.getDeductNo(), ex.getMessage());
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        String reason = rejectReason(instance.getId());
        if ("ScarMajor".equals(instance.getBizType())) {
            Scar s = scarDao.selectById(instance.getBizId());
            if (s == null || "SENT".equals(s.getStatus())) {
                return;
            }
            s.setStatus("DRAFT");
            s.setApprovalId(instance.getId());
            s.setRemark("发出审批被驳回：" + reason);
            scarDao.updateById(s);
            log.info("major SCAR {} rejected: {}", s.getScarNo(), reason);
        } else {
            ScarDeduction d = deductionDao.selectById(instance.getBizId());
            if (d == null || "CONFIRMED".equals(d.getStatus())) {
                return;
            }
            d.setStatus("PENDING_FINANCE");
            d.setApprovalId(instance.getId());
            d.setRemark("财务确认被驳回：" + reason);
            deductionDao.updateById(d);
            log.info("deduction {} finance rejected: {}", d.getDeductNo(), reason);
        }
    }

    private String rejectReason(String apprId) {
        ApprovalTask t = taskDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, apprId)
                .eq(ApprovalTask::getStatus, "REJECTED")
                .last("LIMIT 1"));
        return t == null || t.getOpinion() == null ? "审批驳回" : t.getOpinion();
    }

    private LocalDate addWorkdays(LocalDate from, int days) {
        LocalDate d = from;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek().getValue() < 6) {
                added++;
            }
        }
        return d;
    }
}
