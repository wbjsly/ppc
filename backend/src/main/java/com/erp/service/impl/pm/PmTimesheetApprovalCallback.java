package com.erp.service.impl.pm;

import com.erp.dao.pm.PmTimesheetDao;
import com.erp.entity.pm.PmTimesheet;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 工时补录审批回调（add-timesheet-management，BR-4.8-17 超 2 周 L2）。
 * BIZ_TYPE=PmBackfill：通过 → BACKFILL→SUBMITTED 进审核队列；驳回 → BACKFILL→DRAFT。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmTimesheetApprovalCallback implements ApprovalCallback {

    public static final String BIZ_BACKFILL = PmTimesheetServiceImpl.BIZ_BACKFILL;

    private final PmTimesheetDao tsDao;
    private final NoticeService noticeService;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_BACKFILL);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        PmTimesheet sheet = tsDao.selectById(instance.getBizId());
        if (sheet == null) {
            log.warn("timesheet backfill approved but record missing: {}", instance.getBizId());
            return;
        }
        PmTimesheetServiceImpl.backfillApproved(sheet);
        sheet.setUpdateBy("system");
        sheet.setUpdateDate(LocalDateTime.now());
        tsDao.updateById(sheet);
        noticeService.push("ROLE_USER", sheet.getEmpId(),
                "工时补录审批通过",
                "你 " + sheet.getWorkDate() + " 的补录工时单已通过 PMO 审批，现可进入主管审核流程。",
                BIZ_BACKFILL, sheet.getId());
        log.info("timesheet backfill approved -> SUBMITTED: {} {}", sheet.getId(), sheet.getWorkDate());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        PmTimesheet sheet = tsDao.selectById(instance.getBizId());
        if (sheet == null) {
            log.warn("timesheet backfill rejected but record missing: {}", instance.getBizId());
            return;
        }
        PmTimesheetServiceImpl.backfillRejected(sheet);
        sheet.setRejectReason(lastRejectOpinion(instance.getId()));
        sheet.setUpdateBy("system");
        sheet.setUpdateDate(LocalDateTime.now());
        tsDao.updateById(sheet);
        noticeService.push("ROLE_USER", sheet.getEmpId(),
                "工时补录审批驳回",
                "你 " + sheet.getWorkDate() + " 的补录工时单被驳回：" + sheet.getRejectReason(),
                BIZ_BACKFILL, sheet.getId());
        log.info("timesheet backfill rejected -> DRAFT: {}", sheet.getId());
    }

    private String lastRejectOpinion(String apprId) {
        var tasks = taskDao.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<com.erp.entity.system.ApprovalTask>()
                .eq(com.erp.entity.system.ApprovalTask::getApprId, apprId)
                .eq(com.erp.entity.system.ApprovalTask::getStatus, "REJECTED"));
        if (tasks.isEmpty()) {
            return null;
        }
        return tasks.get(tasks.size() - 1).getOpinion();
    }

    private final com.erp.dao.system.ApprovalTaskDao taskDao;
}
