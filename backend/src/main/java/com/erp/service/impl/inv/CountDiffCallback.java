package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvCheckDiffDao;
import com.erp.dao.inv.InvCountLineDao;
import com.erp.dao.inv.InvCountTaskDao;
import com.erp.entity.inv.InvCheckDiff;
import com.erp.entity.inv.InvCountLine;
import com.erp.entity.inv.InvCountTask;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.inv.CountAdjustService;
import com.erp.service.inv.CountTaskService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CountDiff 盘点差异审批回调（spec count-management 审批与自动调整执行，design D4；与签署同事务）。
 * 通过：按行差异正负拆 ADJUST_OUT/ADJUST_IN 过引擎 + 双凭证（共享调整通道）→
 *       差异单 RESOLVED、行 ADJUSTED、任务 tryComplete（全部行闭环 → DONE + 报告物化）。
 * 驳回：差异单回 PENDING、行标 RECOUNT（待复盘）、任务回 COUNTING、驳回意见留痕。
 * 单差异单行数 >200 → 分批执行（design 风险条：事务时长上限）。
 */
@Slf4j
@Component
public class CountDiffCallback implements ApprovalCallback {

    /** 单批调整行数上限（design D4 风险缓解） */
    private static final int BATCH_LIMIT = 200;

    private final InvCheckDiffDao diffDao;
    private final InvCountLineDao lineDao;
    private final InvCountTaskDao taskDao;
    private final CountAdjustService adjustService;
    private final CountTaskService taskService;

    public CountDiffCallback(InvCheckDiffDao diffDao, InvCountLineDao lineDao,
                             InvCountTaskDao taskDao, CountAdjustService adjustService,
                             CountTaskService taskService) {
        this.diffDao = diffDao;
        this.lineDao = lineDao;
        this.taskDao = taskDao;
        this.adjustService = adjustService;
        this.taskService = taskService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(CountInputServiceImpl.BIZ_COUNT_DIFF);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        InvCheckDiff diff = diffDao.selectById(instance.getBizId());
        if (diff == null) {
            log.warn("count diff approved but record missing: {}", instance.getBizId());
            return;
        }
        if (InvCheckDiff.ST_RESOLVED.equals(diff.getStatus())) {
            return;   // 幂等
        }
        InvCountTask task = taskDao.selectById(diff.getSrcTaskId());
        if (task == null) {
            throw new ServiceException(422, "差异关联盘点任务不存在："
                    + diff.getSrcDocNo());
        }
        List<InvCountLine> lines = lineDao.selectList(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getDiffId, diff.getId())
                .ne(InvCountLine::getCountStatus, InvCountLine.ST_ADJUSTED));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "差异单无可调整盘点行（可能已调整）");
        }
        // 分批执行（每批 ≤200 行，design 风险条）
        for (int i = 0; i < lines.size(); i += BATCH_LIMIT) {
            List<InvCountLine> batch = lines.subList(i, Math.min(i + BATCH_LIMIT, lines.size()));
            adjustService.executeAdjustment(task, batch,
                    "盘点差异审批通过 " + diff.getDiffNo());
        }
        diff.setStatus(InvCheckDiff.ST_RESOLVED);
        diff.setResolveNote("审批通过，自动调整执行完毕（" + diff.getDiffNo() + "）");
        diff.setResolveBy(SecurityUtils.getCurrentUserId());
        diff.setResolveAt(LocalDateTime.now());
        if (diffDao.updateById(diff) == 0) {
            throw new ServiceException(409, "差异单状态冲突，请刷新重试");
        }
        boolean done = taskService.tryComplete(task.getId());
        log.info("count diff {} approved: {} line(s) adjusted, task done={}",
                diff.getDiffNo(), lines.size(), done);
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        InvCheckDiff diff = diffDao.selectById(instance.getBizId());
        if (diff == null) {
            log.warn("count diff rejected but record missing: {}", instance.getBizId());
            return;
        }
        // 行标待复盘（重盘重录后可重新提交）
        List<InvCountLine> lines = lineDao.selectList(new LambdaQueryWrapper<InvCountLine>()
                .eq(InvCountLine::getDiffId, diff.getId()));
        for (InvCountLine l : lines) {
            l.setCountStatus(InvCountLine.ST_RECOUNT);
            l.setRemark(concat(l.getRemark(), "审批驳回，待复盘重录"));
            lineDao.updateById(l);
        }
        // 差异单回 PENDING（驳回意见存 DIFF_NOTE 追加）
        diff.setStatus(InvCheckDiff.ST_PENDING);
        diff.setDiffNote(concat(diff.getDiffNote(), "审批驳回意见：" + safeOpinion(instance)));
        diffDao.updateById(diff);
        // 任务回 COUNTING（复盘中）
        InvCountTask task = taskDao.selectById(diff.getSrcTaskId());
        if (task != null && InvCountTask.ST_ADJUSTING.equals(task.getStatus())) {
            task.setStatus(InvCountTask.ST_COUNTING);
            taskDao.updateById(task);
        }
        log.info("count diff {} rejected → PENDING, lines RECOUNT", diff.getDiffNo());
    }

    private static String safeOpinion(ApprovalInstance inst) {
        // 实例驳回意见由底座记录在日志；此处以固定语 + 实例状态留痕
        return "（实例 " + inst.getApprNo() + " 驳回）";
    }

    private static String concat(String old, String add) {
        if (old == null || old.isEmpty()) {
            return add;
        }
        return old + "；" + add;
    }
}
