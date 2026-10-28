package com.erp.service.impl.scm;

import com.erp.common.ServiceException;
import com.erp.dao.scm.CpfrAdjustLogDao;
import com.erp.dao.scm.CpfrPlanLineDao;
import com.erp.entity.scm.CpfrAdjustLog;
import com.erp.entity.scm.CpfrPlanLine;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * CPFR 冻结期/超弹性调整审批回调（spec cpfr-collaboration R6，C-4.9-08）。
 * 通过 → 从审批标题外的留痕取新数量？不——新数量存 adjust_log ADJUST_SUBMIT 行，
 * 回调按最新 ADJUST_SUBMIT 的 NEW_QTY 写回（LambdaUpdateWrapper 绕 @Version 陷阱，12.5 教训）。
 */
@Slf4j
@Component
public class CpfrAdjustApprovalCallback implements ApprovalCallback {

    private final CpfrPlanLineDao lineDao;
    private final CpfrAdjustLogDao adjustLogDao;

    public CpfrAdjustApprovalCallback(CpfrPlanLineDao lineDao, CpfrAdjustLogDao adjustLogDao) {
        this.lineDao = lineDao;
        this.adjustLogDao = adjustLogDao;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(CpfrPlanServiceImpl.BIZ_CPFR_ADJUST);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        CpfrPlanLine line = lineDao.selectById(instance.getBizId());
        if (line == null) {
            log.warn("CpfrPlanAdjust approved but line missing: {}", instance.getBizId());
            return;
        }
        if (!CpfrPlanLine.ADJ_PENDING.equals(line.getAdjustStatus())) {
            return; // 幂等
        }
        BigDecimal newQty = latestSubmitQty(line.getId());
        if (newQty == null) {
            throw new ServiceException(422, "冻结期调整审批通过但未找到调整申请留痕");
        }
        BigDecimal oldQty = line.getQty();
        lineDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CpfrPlanLine>()
                .eq(CpfrPlanLine::getId, line.getId())
                .set(CpfrPlanLine::getQty, newQty)
                .set(CpfrPlanLine::getAdjustStatus, (String) null));
        CpfrAdjustLog logRow = new CpfrAdjustLog();
        logRow.setTargetType(CpfrAdjustLog.TT_PLAN_LINE);
        logRow.setTargetId(line.getId());
        logRow.setAgreementId(line.getAgreementId());
        logRow.setPeriodTag(line.getPeriodTag());
        logRow.setAction(CpfrAdjustLog.ACT_ADJUST_APPROVED);
        logRow.setOldQty(oldQty);
        logRow.setNewQty(newQty);
        logRow.setRemark("PMGR 审批通过，数量更新");
        logRow.setOperatedAt(LocalDateTime.now());
        adjustLogDao.insert(logRow);
        log.info("CPFR 冻结期调整审批通过：行 {} {} → {}", line.getId(), oldQty, newQty);
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        CpfrPlanLine line = lineDao.selectById(instance.getBizId());
        if (line == null || !CpfrPlanLine.ADJ_PENDING.equals(line.getAdjustStatus())) {
            return;
        }
        lineDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CpfrPlanLine>()
                .eq(CpfrPlanLine::getId, line.getId())
                .set(CpfrPlanLine::getAdjustStatus, (String) null));
        CpfrAdjustLog logRow = new CpfrAdjustLog();
        logRow.setTargetType(CpfrAdjustLog.TT_PLAN_LINE);
        logRow.setTargetId(line.getId());
        logRow.setAgreementId(line.getAgreementId());
        logRow.setPeriodTag(line.getPeriodTag());
        logRow.setAction(CpfrAdjustLog.ACT_ADJUST_REJECTED);
        logRow.setOldQty(line.getQty());
        logRow.setRemark("PMGR 审批驳回，原数量保持锁定");
        logRow.setOperatedAt(LocalDateTime.now());
        adjustLogDao.insert(logRow);
        log.info("CPFR 冻结期调整审批驳回：行 {}（原数量保持）", line.getId());
    }

    /** 最新一条 ADJUST_SUBMIT 的 NEW_QTY（提交时已留痕） */
    private BigDecimal latestSubmitQty(String lineId) {
        var logs = adjustLogDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CpfrAdjustLog>()
                        .eq(CpfrAdjustLog::getTargetType, CpfrAdjustLog.TT_PLAN_LINE)
                        .eq(CpfrAdjustLog::getTargetId, lineId)
                        .eq(CpfrAdjustLog::getAction, CpfrAdjustLog.ACT_ADJUST_SUBMIT)
                        .orderByDesc(CpfrAdjustLog::getOperatedAt)
                        .last("LIMIT 1"));
        return logs.isEmpty() ? null : logs.get(0).getNewQty();
    }
}
