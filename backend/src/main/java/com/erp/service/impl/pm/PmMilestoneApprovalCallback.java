package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.PmMilestoneDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.pm.PmMilestone;
import com.erp.entity.pm.PmWbs;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 里程碑审批回调（add-milestone-management，design D3）。
 * BIZ_TYPE=PmMilestone：通过 → DONE + 实际完成日 + 阶段回写（阶段名下全部里程碑 DONE 才置完成）；
 * 驳回 → REWORK + 意见。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmMilestoneApprovalCallback implements ApprovalCallback {

    public static final String BIZ_MILESTONE = PmMilestoneServiceImpl.BIZ_MILESTONE;

    private final PmMilestoneDao msDao;
    private final PmWbsDao wbsDao;
    private final ApprovalTaskDao taskDao;
    private final NoticeService noticeService;

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_MILESTONE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        PmMilestone m = msDao.selectById(instance.getBizId());
        if (m == null) {
            log.warn("milestone approved but record missing: {}", instance.getBizId());
            return;
        }
        // APPROVED → DONE（含实际完成日；状态机单点语义由回调承接，非法态拒绝对齐 markDone）
        PmMilestoneServiceImpl.markDone(m);
        m.setUpdateBy("system");
        m.setUpdateDate(LocalDateTime.now());
        msDao.updateById(m);
        // 阶段回写：该阶段名下全部里程碑 DONE 才置阶段已完成（阶段可挂多里程碑）
        phaseWriteBack(m.getWbsId());
        // 通知项目经理与 PMO
        noticeService.push("ROLE_PM", null,
                "里程碑完成：" + m.getName(),
                "实际完成日 " + m.getActualEndDate() + "，审批已通过。",
                BIZ_MILESTONE, m.getId());
        noticeService.push("ROLE_PMO", null,
                "里程碑完成：" + m.getName(),
                "审批通过，里程碑已置 DONE。",
                BIZ_MILESTONE, m.getId());
        log.info("milestone approved -> DONE: {} {}", m.getId(), m.getName());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        PmMilestone m = msDao.selectById(instance.getBizId());
        if (m == null) {
            log.warn("milestone rejected but record missing: {}", instance.getBizId());
            return;
        }
        String opinion = lastRejectOpinion(instance.getId());
        m.setStatus(PmMilestone.ST_REWORK);
        m.setRejectOpinion(opinion);
        m.setUpdateBy("system");
        m.setUpdateDate(LocalDateTime.now());
        msDao.updateById(m);
        noticeService.push("ROLE_PM", null,
                "里程碑审批驳回：" + m.getName(),
                "驳回意见：" + opinion + "，请返工后重新提交。",
                BIZ_MILESTONE, m.getId());
        log.info("milestone rejected -> REWORK: {} {}", m.getId(), m.getName());
    }

    /** 取最后一个驳回节点意见（既有 PmApprovalCallback 同款 helper） */
    private String lastRejectOpinion(String apprId) {
        java.util.List<ApprovalTask> tasks = taskDao.selectList(
                new LambdaQueryWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getApprId, apprId)
                        .eq(ApprovalTask::getStatus, "REJECTED"));
        if (tasks.isEmpty()) {
            return null;
        }
        return tasks.get(tasks.size() - 1).getOpinion();
    }

    /** 阶段回写：阶段名下（同 WBS_ID）全部里程碑 DONE 才置阶段已完成 */
    private void phaseWriteBack(String wbsId) {
        Long total = msDao.selectCount(new LambdaQueryWrapper<PmMilestone>()
                .eq(PmMilestone::getWbsId, wbsId)
                .eq(PmMilestone::getDelFlag, "0"));
        Long done = msDao.selectCount(new LambdaQueryWrapper<PmMilestone>()
                .eq(PmMilestone::getWbsId, wbsId)
                .eq(PmMilestone::getStatus, PmMilestone.ST_DONE)
                .eq(PmMilestone::getDelFlag, "0"));
        PmWbs phase = wbsDao.selectById(wbsId);
        if (phase != null && total != null && total > 0 && total.equals(done)) {
            phase.setPhaseDone("1");
            phase.setPhaseDoneDate(java.time.LocalDate.now());
            phase.setUpdateBy("system");
            phase.setUpdateDate(LocalDateTime.now());
            wbsDao.updateById(phase);
            log.info("phase all milestones DONE: {} ({} / {})", wbsId, done, total);
        }
    }
}
