package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.PmBaselineVersionDao;
import com.erp.dao.pm.PmBudgetChangeDao;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmProjectChangeDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.crm.Contract;
import com.erp.entity.pm.PmBaselineVersion;
import com.erp.entity.pm.PmBudgetChange;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmProjectChange;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.crm.ContractService;
import com.erp.service.system.NoticeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 项目变更审批回调（add-project-change，design D3/D5）。
 * BIZ_TYPE=PmProjectChange：通过 → 基线版本化（全量快照写 erp_pm_baseline_version，
 *   CURRENT_FLAG 旧 0 新 1，涉合同升主版本，否则次版本 +1）+ 预算类变更复用快照落基线
 *   + 涉合同联动销售域 ContractService.change + 通知项目团队 + 变更单置 EXECUTING；
 * 驳回 → 变更单置 REJECTED。
 * 只注入 DAO + NoticeService + crm ContractService（避免审批引擎构造循环）。
 */
@Slf4j
@Component
public class PmChangeApprovalCallback implements ApprovalCallback {

    public static final String BIZ_PROJECT_CHANGE = PmProjectChangeServiceImpl.BIZ_PROJECT_CHANGE;

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PmProjectChangeDao changeDao;
    private final PmProjectDao projectDao;
    private final PmBudgetLineDao lineDao;
    private final PmBaselineVersionDao versionDao;
    private final PmBudgetChangeDao budgetChangeDao;
    private final ApprovalTaskDao taskDao;
    private final NoticeService noticeService;
    /** 延迟解析：直接注入 ContractService 会经 ArService→CashDoc→SupplierStatement→ApprovalService 成环 */
    private final org.springframework.beans.factory.ObjectProvider<ContractService> contractServiceProvider;

    public PmChangeApprovalCallback(PmProjectChangeDao changeDao,
                                    PmProjectDao projectDao,
                                    PmBudgetLineDao lineDao,
                                    PmBaselineVersionDao versionDao,
                                    PmBudgetChangeDao budgetChangeDao,
                                    ApprovalTaskDao taskDao,
                                    NoticeService noticeService,
                                    org.springframework.beans.factory.ObjectProvider<ContractService> contractServiceProvider) {
        this.changeDao = changeDao;
        this.projectDao = projectDao;
        this.lineDao = lineDao;
        this.versionDao = versionDao;
        this.budgetChangeDao = budgetChangeDao;
        this.taskDao = taskDao;
        this.noticeService = noticeService;
        this.contractServiceProvider = contractServiceProvider;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_PROJECT_CHANGE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        PmProjectChange c = changeDao.selectById(instance.getBizId());
        if (c == null) {
            log.warn("project change approved but record missing: {}", instance.getBizId());
            return;
        }
        PmProject p = projectDao.selectById(c.getProjectId());
        // 1) 预算类/组合类变更：复用基线快照落基线（BUDGET_ADD>0 时加到首条任务级预算行，沿 approveBudgetChange 口径）
        if (c.getBudgetAdd() != null && c.getBudgetAdd().signum() > 0 && p != null) {
            applyBudgetAdd(c, p);
        }
        // 2) 基线版本化（design D3：全量快照 + CURRENT_FLAG 切换 + 版本号规则）
        PmBaselineVersion v = versionBaseline(c, p);
        // 3) 涉合同联动（design D5，BR-4.8-33）
        if ("1".equals(c.getIsContractFlag()) && hasText(c.getContractId())) {
            linkContractChange(c);
        }
        // 4) 变更单置 EXECUTING + 写版本号
        c.setStatus(PmProjectChange.ST_EXECUTING);
        c.setVerNoLabel(v == null ? null : v.getVerNoLabel());
        c.setUpdateBy("system");
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        // 5) 通知项目团队（design D3）
        try {
            noticeService.push(null, p == null ? null : p.getPmUserId(),
                    "项目变更审批通过：" + (p == null ? c.getProjectId() : p.getName()),
                    "变更单 " + c.getChangeNo() + "（" + c.getChangeType() + "）已通过审批，"
                            + "基线版本 " + (v == null ? "-" : v.getVerNoLabel()) + " 已生效，请推进变更落地（执行中 → 已完成 → 已验证）。",
                    BIZ_PROJECT_CHANGE, c.getId());
        } catch (Exception e) {
            log.warn("project change notice failed: {}", e.getMessage());
        }
        log.info("project change approved -> EXECUTING: {} verLabel={}", c.getId(),
                v == null ? null : v.getVerNoLabel());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        PmProjectChange c = changeDao.selectById(instance.getBizId());
        if (c == null) {
            log.warn("project change rejected but record missing: {}", instance.getBizId());
            return;
        }
        c.setStatus(PmProjectChange.ST_REJECTED);
        c.setPmOpinion(lastRejectOpinion(instance.getId()));
        c.setUpdateBy("system");
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        PmProject p = projectDao.selectById(c.getProjectId());
        try {
            noticeService.push(null, c.getCreateBy(),
                    "项目变更审批驳回：" + (p == null ? c.getProjectId() : p.getName()),
                    "变更单 " + c.getChangeNo() + " 被驳回：" + c.getPmOpinion() + "。请修改后重新提交。",
                    BIZ_PROJECT_CHANGE, c.getId());
        } catch (Exception e) {
            log.warn("project change reject notice failed: {}", e.getMessage());
        }
        log.info("project change rejected: {}", c.getId());
    }

    // ================================================================
    // private：基线版本化 + 预算落库 + 合同联动
    // ================================================================

    /** 预算类变更：追加额加到首条任务级预算行 + 重算基线（沿 PmApprovalCallback.approveBudgetChange 口径）+ budget_change 留痕 */
    private void applyBudgetAdd(PmProjectChange c, PmProject p) {
        PmBudgetLine first = lineDao.selectOne(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, c.getProjectId())
                .eq(PmBudgetLine::getDelFlag, "0")
                .orderByAsc(PmBudgetLine::getCreateDate)
                .last("LIMIT 1"));
        if (first == null) {
            log.warn("project change budget add but no budget line: {}", c.getProjectId());
            return;
        }
        BigDecimal before = p.getBaselineAmount();
        BigDecimal oldLabor = first.getLabor() == null ? BigDecimal.ZERO : first.getLabor();
        first.setLabor(oldLabor.add(c.getBudgetAdd()));
        first.setUpdateBy("system");
        first.setUpdateDate(LocalDateTime.now());
        lineDao.updateById(first);
        BigDecimal after = recomputeBaseline(c.getProjectId());
        p.setBaselineAmount(after);
        p.setUpdateDate(LocalDateTime.now());
        projectDao.updateById(p);
        // 预算变更留痕（沿 erp_pm_budget_change 口径，11.3.3 零回归）
        PmBudgetChange bc = new PmBudgetChange();
        bc.setId(uuid());
        bc.setChangeNo("PC" + c.getChangeNo());
        bc.setProjectId(c.getProjectId());
        bc.setReason("项目变更追加（变更单 " + c.getChangeNo() + "）：" + c.getReason());
        bc.setBeforeAmount(before == null ? BigDecimal.ZERO : before);
        bc.setAfterAmount(after);
        bc.setSnapshotJson("[{\"wbsId\":\"" + first.getWbsId() + "\",\"labor\":\""
                + first.getLabor() + "\"}]");
        bc.setStatus(PmBudgetChange.ST_APPROVED);
        bc.setCreateBy("system");
        bc.setCreateDate(LocalDateTime.now());
        budgetChangeDao.insert(bc);
    }

    /** 基线版本化（design D3）：读当前预算行全量快照 → 版本号（涉合同升主版本，否则次版本+1）→ 插新版本行（CURRENT=1）+ 旧 CURRENT 置 0 */
    private PmBaselineVersion versionBaseline(PmProjectChange c, PmProject p) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PmBudgetLine l : lineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, c.getProjectId())
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wbsId", l.getWbsId());
            row.put("labor", nz(l.getLabor()));
            row.put("material", nz(l.getMaterial()));
            row.put("subcontract", nz(l.getSubcontract()));
            row.put("travel", nz(l.getTravel()));
            row.put("other", nz(l.getOther()));
            row.put("indirect", nz(l.getIndirect()));
            snapshot.add(row);
            total = total.add(nz(l.getLabor())).add(nz(l.getMaterial())).add(nz(l.getSubcontract()))
                    .add(nz(l.getTravel())).add(nz(l.getOther())).add(nz(l.getIndirect()));
        }
        // 版本号规则：涉合同 → 主版本 +1；否则次版本 +1
        PmBaselineVersion current = versionDao.selectOne(new LambdaQueryWrapper<PmBaselineVersion>()
                .eq(PmBaselineVersion::getProjectId, c.getProjectId())
                .eq(PmBaselineVersion::getCurrentFlag, "1")
                .eq(PmBaselineVersion::getDelFlag, "0")
                .last("LIMIT 1"));
        int major = current == null || current.getMajorVer() == null ? 1 : current.getMajorVer();
        int minor = current == null || current.getMinorVer() == null ? 0 : current.getMinorVer();
        if ("1".equals(c.getIsContractFlag())) {
            major += 1;
            minor = 0;
        } else {
            minor += 1;
        }
        // 旧 CURRENT 置 0
        if (current != null) {
            current.setCurrentFlag("0");
            current.setUpdateBy("system");
            current.setUpdateDate(LocalDateTime.now());
            versionDao.updateById(current);
        }
        PmBaselineVersion v = new PmBaselineVersion();
        v.setId(uuid());
        v.setProjectId(c.getProjectId());
        v.setMajorVer(major);
        v.setMinorVer(minor);
        v.setVerNoLabel("V" + major + "." + minor);
        try {
            v.setSnapshotJson(mapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw new IllegalStateException("基线版本快照序列化失败：" + e.getMessage(), e);
        }
        v.setBaselineAmount(total.signum() > 0 ? total
                : (p != null && p.getBaselineAmount() != null ? p.getBaselineAmount() : BigDecimal.ZERO));
        v.setCurrentFlag("1");
        v.setChangeId(c.getId());
        v.setCreateBy("system");
        v.setCreateDate(LocalDateTime.now());
        versionDao.insert(v);
        return v;
    }

    /** 涉合同联动（design D5，BR-4.8-33）：调销售域 ContractService.change 自动建合同变更单，回填版本 ID */
    private void linkContractChange(PmProjectChange c) {
        try {
            ContractService contractService = contractServiceProvider.getObject();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("changeReason", "项目变更 " + c.getChangeNo() + "：" + c.getReason());
            if (c.getBudgetAdd() != null && c.getBudgetAdd().signum() > 0) {
                payload.put("amountDelta", c.getBudgetAdd());
            }
            Contract contract = contractService.change(c.getContractId(), payload);
            if (contract != null) {
                // 回填销售域合同变更版本 ID（取最新版本）
                List<Map<String, Object>> versions = contractService.versions(c.getContractId());
                String latestVerId = null;
                if (versions != null && !versions.isEmpty()) {
                    Object idObj = versions.get(0).get("id");
                    latestVerId = idObj == null ? null : String.valueOf(idObj);
                }
                c.setContractChangeVerId(latestVerId);
                c.setUpdateBy("system");
                c.setUpdateDate(LocalDateTime.now());
                changeDao.updateById(c);
            }
        } catch (Exception e) {
            // 联动失败整体回滚（design D5：不出现"项目变更过了、合同没建"）
            throw new IllegalStateException("合同级变更联动失败（BR-4.8-33）：" + e.getMessage(), e);
        }
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

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
