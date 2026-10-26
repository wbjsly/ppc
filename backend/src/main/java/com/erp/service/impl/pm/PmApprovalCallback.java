package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.PmBudgetChangeDao;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.pm.PmBudgetChange;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmProject;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.system.NoticeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 项目审批回调（project-initiation，design D5）。
 * PmBaseline：通过 → 锁定基线 + ACTIVE + 编号生成 + 通知；驳回 → DRAFT + 意见。
 * PmBudgetChange：通过 → 按快照替换基线；驳回 → REJECTED 基线不变。
 * 只注入 DAO + NoticeService + 编号生成器（不依赖业务服务，避免审批引擎构造循环）。
 */
@Slf4j
@Component
public class PmApprovalCallback implements ApprovalCallback {

    public static final String BIZ_BASELINE = "PmBaseline";
    public static final String BIZ_BUDGET_CHANGE = "PmBudgetChange";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PmProjectDao projectDao;
    private final PmBudgetLineDao lineDao;
    private final PmBudgetChangeDao changeDao;
    private final ApprovalTaskDao taskDao;
    private final NoticeService noticeService;
    private final PmProjectNoGenerator noGenerator;

    public PmApprovalCallback(PmProjectDao projectDao,
                              PmBudgetLineDao lineDao,
                              PmBudgetChangeDao changeDao,
                              ApprovalTaskDao taskDao,
                              NoticeService noticeService,
                              PmProjectNoGenerator noGenerator) {
        this.projectDao = projectDao;
        this.lineDao = lineDao;
        this.changeDao = changeDao;
        this.taskDao = taskDao;
        this.noticeService = noticeService;
        this.noGenerator = noGenerator;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_BASELINE, BIZ_BUDGET_CHANGE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        if (BIZ_BASELINE.equals(instance.getBizType())) {
            approveBaseline(instance);
        } else if (BIZ_BUDGET_CHANGE.equals(instance.getBizType())) {
            approveBudgetChange(instance);
        }
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        String opinion = lastRejectOpinion(instance.getId());
        if (BIZ_BASELINE.equals(instance.getBizType())) {
            PmProject p = projectDao.selectById(instance.getBizId());
            if (p == null) {
                log.warn("pm baseline rejected but project missing: {}", instance.getBizId());
                return;
            }
            p.setStatus(PmProject.ST_DRAFT);
            p.setRejectOpinion(opinion);
            p.setUpdateDate(LocalDateTime.now());
            projectDao.updateById(p);
        } else if (BIZ_BUDGET_CHANGE.equals(instance.getBizType())) {
            PmBudgetChange c = changeDao.selectById(instance.getBizId());
            if (c == null) {
                log.warn("pm budget change rejected but record missing: {}", instance.getBizId());
                return;
            }
            c.setStatus(PmBudgetChange.ST_REJECTED);
            c.setFinOpinion(opinion);
            c.setUpdateDate(LocalDateTime.now());
            changeDao.updateById(c);
        }
    }

    // ================================================================
    // private
    // ================================================================

    /** 基线通过：锁定 + ACTIVE + 编号 + 通知 */
    private void approveBaseline(ApprovalInstance instance) {
        PmProject p = projectDao.selectById(instance.getBizId());
        if (p == null) {
            log.warn("pm baseline approved but project missing: {}", instance.getBizId());
            return;
        }
        // 编号仅此刻生成（对齐"审批通过，项目编号已生成"）
        if (p.getProjectNo() == null || p.getProjectNo().isBlank()) {
            p.setProjectNo(noGenerator.next());
        }
        p.setBaselineLocked("1");
        p.setStatus(PmProject.ST_ACTIVE);
        p.setRejectOpinion(null);
        p.setUpdateDate(LocalDateTime.now());
        projectDao.updateById(p);
        // 通知 PM 与 PMO（站内）
        try {
            noticeService.push(null, p.getPmUserId(),
                    "项目启动：" + p.getName(),
                    "预算基线已审批通过，项目 " + p.getProjectNo() + " 正式启动，基线金额 "
                            + p.getBaselineAmount() + " 元已锁定。",
                    BIZ_BASELINE, p.getId());
            noticeService.push("ROLE_PMO", null,
                    "项目基线审批通过：" + p.getName(),
                    "项目 " + p.getProjectNo() + " 基线 " + p.getBaselineAmount() + " 元已锁定并启动。",
                    BIZ_BASELINE, p.getId());
        } catch (Exception e) {
            // 通知失败不回滚审批（与既有回调惯例一致）
            log.warn("pm baseline notice failed: {}", e.getMessage());
        }
        log.info("pm baseline approved: {} -> {} ACTIVE", p.getId(), p.getProjectNo());
    }

    /** 变更通过：按快照替换基线 */
    private void approveBudgetChange(ApprovalInstance instance) {
        PmBudgetChange c = changeDao.selectById(instance.getBizId());
        if (c == null) {
            log.warn("pm budget change approved but record missing: {}", instance.getBizId());
            return;
        }
        c.setStatus(PmBudgetChange.ST_APPROVED);
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        // 快照替换预算行
        try {
            JsonNode arr = mapper.readTree(c.getSnapshotJson());
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String wbsId = n.path("wbsId").asText(null);
                    if (wbsId == null) {
                        continue;
                    }
                    PmBudgetLine line = lineDao.selectOne(new LambdaQueryWrapper<PmBudgetLine>()
                            .eq(PmBudgetLine::getWbsId, wbsId)
                            .eq(PmBudgetLine::getDelFlag, "0")
                            .last("LIMIT 1"));
                    if (line == null) {
                        line = new PmBudgetLine();
                        line.setId(UUID.randomUUID().toString().replace("-", ""));
                        line.setProjectId(c.getProjectId());
                        line.setWbsId(wbsId);
                        line.setCreateBy("system");
                        line.setCreateDate(LocalDateTime.now());
                        line.setLabor(BigDecimal.ZERO);
                        line.setMaterial(BigDecimal.ZERO);
                        line.setSubcontract(BigDecimal.ZERO);
                        line.setTravel(BigDecimal.ZERO);
                        line.setOther(BigDecimal.ZERO);
                        line.setIndirect(BigDecimal.ZERO);
                    }
                    line.setLabor(dec(n.path("labor")));
                    line.setMaterial(dec(n.path("material")));
                    line.setSubcontract(dec(n.path("subcontract")));
                    line.setTravel(dec(n.path("travel")));
                    line.setOther(dec(n.path("other")));
                    line.setIndirect(dec(n.path("indirect")));
                    line.setUpdateBy("system");
                    line.setUpdateDate(LocalDateTime.now());
                    if (line.getId() != null && lineDao.selectById(line.getId()) != null) {
                        lineDao.updateById(line);
                    } else {
                        lineDao.insert(line);
                    }
                }
            }
            // 更新项目基线金额
            PmProject p = projectDao.selectById(c.getProjectId());
            if (p != null) {
                BigDecimal total = BigDecimal.ZERO;
                for (PmBudgetLine l : lineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                        .eq(PmBudgetLine::getProjectId, c.getProjectId())
                        .eq(PmBudgetLine::getDelFlag, "0"))) {
                    total = total.add(nz(l.getLabor())).add(nz(l.getMaterial())).add(nz(l.getSubcontract()))
                            .add(nz(l.getTravel())).add(nz(l.getOther())).add(nz(l.getIndirect()));
                }
                p.setBaselineAmount(total);
                p.setUpdateDate(LocalDateTime.now());
                projectDao.updateById(p);
            }
        } catch (Exception e) {
            // 快照解析失败 → 回滚整单（回调异常 → 审批与业务一并回滚）
            throw new IllegalStateException("预算变更快照应用失败：" + e.getMessage(), e);
        }
        log.info("pm budget change approved: {} projectId={}", c.getId(), c.getProjectId());
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

    private static BigDecimal dec(JsonNode n) {
        if (n == null || n.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(n.asText());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
