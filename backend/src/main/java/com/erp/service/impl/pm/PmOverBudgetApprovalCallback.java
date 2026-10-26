package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.PmBudgetChangeDao;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmOverBudgetDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.pm.PmBudgetChange;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmOverBudget;
import com.erp.entity.pm.PmProject;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 超预算审批回调（add-cost-collection-freeze，design D4）。
 * BIZ_TYPE=PmOverBudget：通过 → 解冻（FREEZE_FLAG=0）+ EXTRA_BUDGET>0 时按追加额
 * 更新预算行（直接加到首条任务行，沿 PmApprovalCallback 基线快照口径）+ 写 budget_change 留痕；
 * 驳回 → 保持冻结，通知申请人。
 * 只注入 DAO + NoticeService（不依赖业务服务，避免审批引擎构造循环）。
 */
@Slf4j
@Component
public class PmOverBudgetApprovalCallback implements ApprovalCallback {

    public static final String BIZ_OVER_BUDGET = PmOverBudgetServiceImpl.BIZ_OVER_BUDGET;

    private final PmOverBudgetDao overBudgetDao;
    private final PmProjectDao projectDao;
    private final PmBudgetLineDao lineDao;
    private final PmBudgetChangeDao changeDao;
    private final ApprovalTaskDao taskDao;
    private final NoticeService noticeService;

    public PmOverBudgetApprovalCallback(PmOverBudgetDao overBudgetDao,
                                        PmProjectDao projectDao,
                                        PmBudgetLineDao lineDao,
                                        PmBudgetChangeDao changeDao,
                                        ApprovalTaskDao taskDao,
                                        NoticeService noticeService) {
        this.overBudgetDao = overBudgetDao;
        this.projectDao = projectDao;
        this.lineDao = lineDao;
        this.changeDao = changeDao;
        this.taskDao = taskDao;
        this.noticeService = noticeService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_OVER_BUDGET);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        PmOverBudget ob = overBudgetDao.selectById(instance.getBizId());
        if (ob == null) {
            log.warn("over-budget approved but record missing: {}", instance.getBizId());
            return;
        }
        ob.setStatus(PmOverBudget.ST_APPROVED);
        ob.setUpdateBy("system");
        ob.setUpdateDate(LocalDateTime.now());
        overBudgetDao.updateById(ob);
        // 解冻
        PmProject p = projectDao.selectById(ob.getProjectId());
        BigDecimal beforeBaseline = null;
        BigDecimal afterBaseline = null;
        if (p != null) {
            beforeBaseline = p.getBaselineAmount();
            p.setFreezeFlag(PmProject.FREEZE_NO);
            p.setFreezeReason(null);
            p.setFrozenAt(null);
            p.setUpdateBy("system");
            p.setUpdateDate(LocalDateTime.now());
        }
        // 追加预算 → 更新预算行（复用基线口径：追加额加到首条任务级预算行）+ 留痕
        if (ob.getExtraBudget() != null && ob.getExtraBudget().signum() > 0 && p != null) {
            PmBudgetLine first = lineDao.selectOne(new LambdaQueryWrapper<PmBudgetLine>()
                    .eq(PmBudgetLine::getProjectId, ob.getProjectId())
                    .eq(PmBudgetLine::getDelFlag, "0")
                    .orderByAsc(PmBudgetLine::getCreateDate)
                    .last("LIMIT 1"));
            if (first == null) {
                log.warn("over-budget extra but no budget line: {}", ob.getProjectId());
            } else {
                BigDecimal oldLabor = first.getLabor() == null ? BigDecimal.ZERO : first.getLabor();
                first.setLabor(oldLabor.add(ob.getExtraBudget()));
                first.setUpdateBy("system");
                first.setUpdateDate(LocalDateTime.now());
                lineDao.updateById(first);
                afterBaseline = recomputeBaseline(ob.getProjectId());
                p.setBaselineAmount(afterBaseline);
                // 预算变更留痕（沿 erp_pm_budget_change 口径）
                PmBudgetChange c = new PmBudgetChange();
                c.setId(UUID.randomUUID().toString().replace("-", ""));
                c.setChangeNo("OBB" + ob.getApplyNo());
                c.setProjectId(ob.getProjectId());
                c.setReason("超预算追加（申请单 " + ob.getApplyNo() + "）：" + ob.getReason());
                c.setBeforeAmount(beforeBaseline == null ? BigDecimal.ZERO : beforeBaseline);
                c.setAfterAmount(afterBaseline);
                c.setSnapshotJson("[{\"wbsId\":\"" + first.getWbsId() + "\",\"labor\":\""
                        + first.getLabor() + "\"}]");
                c.setStatus(PmBudgetChange.ST_APPROVED);
                c.setCreateBy("system");
                c.setCreateDate(LocalDateTime.now());
                changeDao.insert(c);
            }
        }
        if (p != null) {
            projectDao.updateById(p);
        }
        try {
            noticeService.push(null, p == null ? null : p.getPmUserId(),
                    "超预算审批通过：" + (p == null ? ob.getProjectId() : p.getName()),
                    "超预算申请 " + ob.getApplyNo() + "（超支 " + ob.getOverAmount() + " 元 / "
                            + ob.getOverRate() + "%）已通过审批，项目已解冻"
                            + (ob.getExtraBudget() != null && ob.getExtraBudget().signum() > 0
                            ? "，追加预算 " + ob.getExtraBudget() + " 元已计入基线" : "") + "。",
                    BIZ_OVER_BUDGET, ob.getId());
        } catch (Exception e) {
            log.warn("over-budget notice failed: {}", e.getMessage());
        }
        log.info("over-budget approved -> unfrozen: {} extra={}", ob.getId(), ob.getExtraBudget());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        PmOverBudget ob = overBudgetDao.selectById(instance.getBizId());
        if (ob == null) {
            log.warn("over-budget rejected but record missing: {}", instance.getBizId());
            return;
        }
        ob.setStatus(PmOverBudget.ST_REJECTED);
        ob.setPmOpinion(lastRejectOpinion(instance.getId()));
        ob.setUpdateBy("system");
        ob.setUpdateDate(LocalDateTime.now());
        overBudgetDao.updateById(ob);
        // 保持冻结，通知申请人制定节约方案（FR-4.8-5-7 异常处理）
        PmProject p = projectDao.selectById(ob.getProjectId());
        try {
            noticeService.push(null, ob.getCreateBy(),
                    "超预算审批驳回：" + (p == null ? ob.getProjectId() : p.getName()),
                    "超预算申请 " + ob.getApplyNo() + " 被驳回：" + ob.getPmOpinion()
                            + "。项目保持冻结，请制定节约方案后重新申请。",
                    BIZ_OVER_BUDGET, ob.getId());
        } catch (Exception e) {
            log.warn("over-budget reject notice failed: {}", e.getMessage());
        }
        log.info("over-budget rejected (project stays frozen): {}", ob.getId());
    }

    private BigDecimal recomputeBaseline(String projectId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PmBudgetLine l : lineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, projectId)
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            sum = sum.add(nz(l.getLabor())).add(nz(l.getMaterial())).add(nz(l.getSubcontract()))
                    .add(nz(l.getTravel())).add(nz(l.getOther())).add(nz(l.getIndirect()));
        }
        return sum;
    }

    private String lastRejectOpinion(String apprId) {
        List<ApprovalTask> tasks = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, apprId)
                .eq(ApprovalTask::getStatus, "REJECTED"));
        if (tasks.isEmpty()) {
            return null;
        }
        return tasks.get(tasks.size() - 1).getOpinion();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
