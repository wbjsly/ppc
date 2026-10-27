package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.ClosureCheckDao;
import com.erp.dao.pm.ClosureCheckItemDao;
import com.erp.dao.pm.ClosureRemediationDao;
import com.erp.dao.pm.ClosureSettlementDao;
import com.erp.dao.pm.PmMilestoneDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.pm.ClosureCheck;
import com.erp.entity.pm.ClosureCheckItem;
import com.erp.entity.pm.ClosureRemediation;
import com.erp.entity.pm.ClosureSettlement;
import com.erp.entity.pm.PmMilestone;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.pm.PmClosureService;
import com.erp.service.pm.PmIpAgreementService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 结项检查实现（add-project-closure，S-4.8-07 / C-4.8-05 / BR-4.8-27）。
 * 六项检查：①WBS 完成度（阶段级 PHASE_DONE 汇总）②里程碑审批 ③交付物验收（人工+附件）
 * ④成本偏差（|dev|≤5%）⑤IP 归属协议 ⑥文档归档（人工+附件）。
 * 自动项落数值证据快照（EVIDENCE_JSON，非布尔）；提交审批后 FROZEN 冻结留档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmClosureServiceImpl implements PmClosureService {

    public static final String BIZ_CLOSURE = "ProjectClosure";
    /** 附件口径：人工项凭证 bizId=itemId；项目总结报告 bizId=projectId */
    public static final String ATTACH_BIZ = "PROJECT_CLOSURE";
    /** 成本偏差容差（绝对值 ≤5%） */
    static final BigDecimal DEVIATION_TOLERANCE = new BigDecimal("0.05");
    /** 遗留工单期限 = 提交日 + 90 天（BR-4.8-28） */
    static final int REMEDIATION_DAYS = 90;

    private final PmProjectDao projectDao;
    private final ClosureCheckDao checkDao;
    private final ClosureCheckItemDao itemDao;
    private final ClosureRemediationDao remediationDao;
    private final ClosureSettlementDao settlementDao;
    private final PmWbsDao wbsDao;
    private final PmMilestoneDao milestoneDao;
    private final ApprovalInstanceDao approvalInstanceDao;
    private final ApprovalTaskDao approvalTaskDao;
    private final ApprovalEngine approvalEngine;
    private final PmIpAgreementService ipAgreementService;
    private final JdbcTemplate jdbcTemplate;

    // ================================================================
    // 发起 / 重新校验
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> initiate(String projectId) {
        requireRole("发起结项", "ROLE_PM");
        ClosureCheck exists = checkOf(projectId);
        if (exists != null) {
            throw new ServiceException(409, "该项目已发起过结项，可在检查单上继续或撤回后重来");
        }
        PmProject p = requireProject(projectId);
        if (!PmProject.ST_ACTIVE.equals(p.getStatus())) {
            throw new ServiceException(422, "仅执行中（ACTIVE）项目可发起结项，当前：" + p.getStatus());
        }
        ClosureCheck c = new ClosureCheck();
        c.setId(uuid());
        c.setProjectId(projectId);
        c.setProjectNo(p.getProjectNo());
        c.setStatus(ClosureCheck.ST_DRAFT);
        c.setConditionalFlag("0");
        c.setCreateBy(SecurityUtils.getCurrentUserId());
        c.setCreateDate(LocalDateTime.now());
        checkDao.insert(c);

        Map<String, Object> ev = computeEvidence(projectId);
        insertFixedItems(c.getId(), ev);

        p.setStatus(PmProject.ST_CLOSING);
        p.setUpdateBy(SecurityUtils.getCurrentUserId());
        p.setUpdateDate(LocalDateTime.now());
        projectDao.updateById(p);

        return detail(projectId);
    }

    @Override
    public Map<String, Object> resubmit(String projectId) {
        requireRole("重新校验", "ROLE_PM");
        ClosureCheck c = requireCheck(projectId);
        if (!ClosureCheck.ST_DRAFT.equals(c.getStatus())) {
            throw new ServiceException(422, "检查单已提交审批，不可重新校验（撤回后可改）");
        }
        Map<String, Object> ev = computeEvidence(projectId);
        List<ClosureCheckItem> items = itemsOf(c.getId());
        for (ClosureCheckItem it : items) {
            if ("1".equals(it.getAutoFlag())) {
                applyEvidence(it, ev);
                it.setUpdateBy(SecurityUtils.getCurrentUserId());
                it.setUpdateDate(LocalDateTime.now());
                itemDao.updateById(it);
            }
        }
        return detail(projectId);
    }

    @Override
    public Map<String, Object> computeEvidence(String projectId) {
        Map<String, Object> ev = new LinkedHashMap<>();
        // ① WBS 完成度（阶段级 PHASE_DONE 汇总；task 级无独立完成标记）
        Long totalPhases = wbsDao.selectCount(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId)
                .eq(PmWbs::getLevel, PmWbs.LV_PHASE)
                .eq(PmWbs::getDelFlag, "0"));
        Long donePhases = wbsDao.selectCount(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getProjectId, projectId)
                .eq(PmWbs::getLevel, PmWbs.LV_PHASE)
                .eq(PmWbs::getPhaseDone, "1")
                .eq(PmWbs::getDelFlag, "0"));
        BigDecimal doneRate = totalPhases == 0 ? BigDecimal.ONE
                : BigDecimal.valueOf(donePhases).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalPhases), 1, RoundingMode.HALF_UP);
        ev.put("doneRate", doneRate);
        ev.put("donePhases", donePhases);
        ev.put("totalPhases", totalPhases);
        // ② 里程碑：非 APPROVED/DONE 计数（无里程碑视同通过——内部小项目场景）
        Long openMs = milestoneDao.selectCount(new LambdaQueryWrapper<PmMilestone>()
                .eq(PmMilestone::getProjectId, projectId)
                .eq(PmMilestone::getDelFlag, "0")
                .notIn(PmMilestone::getStatus, PmMilestone.ST_APPROVED, PmMilestone.ST_DONE));
        ev.put("openMs", openMs);
        // ④ 成本偏差：归集合计 vs 基线
        BigDecimal collect = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(AMOUNT),0) FROM erp_pm_cost_collect WHERE PROJECT_ID=? AND DEL_FLAG='0'",
                BigDecimal.class, projectId);
        PmProject p = requireProject(projectId);
        BigDecimal baseline = p.getBaselineAmount() == null ? BigDecimal.ZERO : p.getBaselineAmount();
        BigDecimal deviation;
        if (baseline.signum() == 0) {
            deviation = collect != null && collect.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        } else {
            deviation = collect.subtract(baseline).divide(baseline, 4, RoundingMode.HALF_UP);
        }
        ev.put("collectTotal", collect == null ? BigDecimal.ZERO : collect);
        ev.put("baselineAmt", baseline);
        ev.put("deviation", deviation);
        // ⑤ IP 归属协议
        int missingIp = ipAgreementService.effectiveOf(projectId) == null ? 1 : 0;
        ev.put("missingIp", missingIp);
        return ev;
    }

    // ================================================================
    // 人工项确认 / 自定义行
    // ================================================================

    @Override
    public Map<String, Object> confirmItem(String itemId, Map<String, Object> payload) {
        requireRole("确认检查项", "ROLE_PM");
        ClosureCheckItem it = itemDao.selectById(itemId);
        if (it == null) {
            throw new ServiceException(404, "检查项不存在：" + itemId);
        }
        ClosureCheck c = checkDao.selectById(it.getCheckId());
        if (c == null || !ClosureCheck.ST_DRAFT.equals(c.getStatus())) {
            throw new ServiceException(422, "检查单已冻结（提交审批后），不可修改");
        }
        if ("1".equals(it.getAutoFlag())) {
            throw new ServiceException(422, "自动校验项不可人工确认，请点「重新校验」刷新");
        }
        String result = str(payload.get("result"));
        if (!ClosureCheckItem.R_PASS.equals(result) && !ClosureCheckItem.R_FAIL.equals(result)) {
            throw new ServiceException(422, "result 须为 PASS 或 FAIL");
        }
        String attachIds = str(payload.get("attachIds"));
        if (ClosureCheckItem.R_PASS.equals(result)) {
            requireAttachments(itemId, "勾选通过必须至少上传 1 份凭证附件");
        }
        it.setResult(result);
        it.setAttachIds(attachIds);
        it.setRemark(str(payload.get("remark")));
        it.setConfirmBy(SecurityUtils.getCurrentUserId());
        it.setConfirmDate(LocalDateTime.now());
        it.setUpdateBy(SecurityUtils.getCurrentUserId());
        it.setUpdateDate(LocalDateTime.now());
        itemDao.updateById(it);
        return itemMap(it);
    }

    @Override
    public Map<String, Object> addCustomItem(String projectId, Map<String, Object> payload) {
        requireRole("添加检查项", "ROLE_PM");
        ClosureCheck c = requireCheck(projectId);
        if (!ClosureCheck.ST_DRAFT.equals(c.getStatus())) {
            throw new ServiceException(422, "检查单已冻结，不可添加检查项");
        }
        String name = str(payload.get("itemName"));
        if (name == null || name.isBlank()) {
            throw new ServiceException(422, "检查项名称必填");
        }
        ClosureCheckItem it = new ClosureCheckItem();
        it.setId(uuid());
        it.setCheckId(c.getId());
        it.setLineNo(nextLineNo(c.getId()));
        it.setItemKey("CUSTOM_" + it.getId().substring(0, 6).toUpperCase());
        it.setItemName(name);
        it.setMandatoryFlag("0");
        it.setAutoFlag("0");
        it.setResult(ClosureCheckItem.R_PENDING);
        it.setCreateBy(SecurityUtils.getCurrentUserId());
        it.setCreateDate(LocalDateTime.now());
        itemDao.insert(it);
        return itemMap(it);
    }

    @Override
    public void removeCustomItem(String itemId) {
        requireRole("删除检查项", "ROLE_PM");
        ClosureCheckItem it = itemDao.selectById(itemId);
        if (it == null || it.getItemKey() == null || !it.getItemKey().startsWith("CUSTOM_")) {
            throw new ServiceException(404, "自定义检查项不存在");
        }
        ClosureCheck c = checkDao.selectById(it.getCheckId());
        if (c == null || !ClosureCheck.ST_DRAFT.equals(c.getStatus())) {
            throw new ServiceException(422, "检查单已冻结，不可删除");
        }
        it.setDelFlag("1");
        it.setUpdateBy(SecurityUtils.getCurrentUserId());
        it.setUpdateDate(LocalDateTime.now());
        itemDao.updateById(it);
    }

    // ================================================================
    // 提交审批 / 撤回
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> submitForApproval(String projectId) {
        requireRole("提交结项审批", "ROLE_PM");
        ClosureCheck c = requireCheck(projectId);
        if (!ClosureCheck.ST_DRAFT.equals(c.getStatus())) {
            throw new ServiceException(422, "检查单已提交或已办结");
        }
        // 强制项全 PASS（C-4.8-05 / BR-4.8-27）
        List<ClosureCheckItem> items = itemsOf(c.getId());
        List<String> notPassed = new ArrayList<>();
        for (ClosureCheckItem it : items) {
            if ("1".equals(it.getMandatoryFlag()) && !ClosureCheckItem.R_PASS.equals(it.getResult())) {
                notPassed.add(it.getItemName());
            }
        }
        if (!notPassed.isEmpty()) {
            throw new ServiceException(422, "强制检查项未通过，不可提交：" + String.join("、", notPassed));
        }
        // 人工项附件二次校验（防直改库绕过；自动项无数值附件概念）
        for (ClosureCheckItem it : items) {
            if ("0".equals(it.getAutoFlag()) && "1".equals(it.getMandatoryFlag())
                    && ClosureCheckItem.R_PASS.equals(it.getResult())) {
                requireAttachments(it.getId(), "「" + it.getItemName() + "」凭证附件缺失");
            }
        }
        // 财务结算前置（11.12.2）
        if (settlementDao.selectCount(new LambdaQueryWrapper<ClosureSettlement>()
                .eq(ClosureSettlement::getProjectId, projectId)
                .eq(ClosureSettlement::getDelFlag, "0")) == 0) {
            throw new ServiceException(422, "请先完成财务成本结算（11.12.2）");
        }
        // 项目总结报告（BR-4.8-30，bizId=projectId）
        requireProjectReport(projectId);

        c.setStatus(ClosureCheck.ST_FROZEN);
        c.setSubmitBy(SecurityUtils.getCurrentUserId());
        c.setSubmitDate(LocalDateTime.now());
        var inst = approvalEngine.submit(
                BIZ_CLOSURE,
                projectId,
                "项目结项审批：" + (c.getProjectNo() != null ? c.getProjectNo() : projectId),
                null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PMO", "PMO 结项审批"))));
        c.setApprovalId(inst.getId());
        c.setUpdateBy(SecurityUtils.getCurrentUserId());
        c.setUpdateDate(LocalDateTime.now());
        checkDao.updateById(c);
        return detail(projectId);
    }

    @Override
    @Transactional
    public Map<String, Object> withdraw(String projectId) {
        requireRole("撤回结项", "ROLE_PM");
        ClosureCheck c = requireCheck(projectId);
        if (!ClosureCheck.ST_FROZEN.equals(c.getStatus())) {
            throw new ServiceException(422, "检查单未在审批中，无需撤回");
        }
        if (c.getApprovalId() != null) {
            ApprovalInstance inst = approvalInstanceDao.selectById(c.getApprovalId());
            if (inst != null && "PENDING".equals(inst.getStatus())) {
                inst.setStatus("CANCELLED");
                inst.setFinishDate(LocalDateTime.now());
                approvalInstanceDao.updateById(inst);
                approvalTaskDao.update(null, new LambdaUpdateWrapper<ApprovalTask>()
                        .eq(ApprovalTask::getApprId, inst.getId())
                        .eq(ApprovalTask::getStatus, "ACTIVE")
                        .set(ApprovalTask::getStatus, "SKIPPED"));
            }
        }
        c.setStatus(ClosureCheck.ST_DRAFT);
        c.setApprovalId(null);
        c.setUpdateBy(SecurityUtils.getCurrentUserId());
        c.setUpdateDate(LocalDateTime.now());
        checkDao.updateById(c);
        PmProject p = requireProject(projectId);
        p.setStatus(PmProject.ST_ACTIVE);
        p.setUpdateBy(SecurityUtils.getCurrentUserId());
        p.setUpdateDate(LocalDateTime.now());
        projectDao.updateById(p);
        return detail(projectId);
    }

    // ================================================================
    // 查询 / 遗留草稿
    // ================================================================

    @Override
    public Map<String, Object> detail(String projectId) {
        ClosureCheck c = checkOf(projectId);
        if (c == null) {
            return Map.of("check", Map.of(), "items", List.of(), "remediations", List.of(),
                    "settlement", settlementOf(projectId));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ClosureCheckItem it : itemsOf(c.getId())) {
            rows.add(itemMap(it));
        }
        return Map.of(
                "check", c,
                "items", rows,
                "remediations", remediations(projectId),
                "settlement", settlementOf(projectId));
    }

    @Override
    public List<Map<String, Object>> remediations(String projectId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<ClosureRemediation> list = remediationDao.selectList(
                new LambdaQueryWrapper<ClosureRemediation>()
                        .eq(ClosureRemediation::getProjectId, projectId)
                        .eq(ClosureRemediation::getDelFlag, "0")
                        .orderByAsc(ClosureRemediation::getDueDate));
        for (ClosureRemediation r : list) {
            out.add(remediationMap(r));
        }
        return out;
    }

    @Override
    @Transactional
    public List<Map<String, Object>> saveRemediationDrafts(String projectId,
                                                           List<Map<String, Object>> items) {
        requireRole("维护遗留工单", "ROLE_PMO");
        ClosureCheck c = requireCheck(projectId);
        if (!ClosureCheck.ST_FROZEN.equals(c.getStatus())) {
            throw new ServiceException(422, "检查单提交审批后方可登记遗留项（审批前草稿）");
        }
        // 全量替换策略：删旧 DRAFT，插新
        List<ClosureRemediation> old = remediationDao.selectList(
                new LambdaQueryWrapper<ClosureRemediation>()
                        .eq(ClosureRemediation::getProjectId, projectId)
                        .eq(ClosureRemediation::getStatus, ClosureRemediation.ST_DRAFT)
                        .eq(ClosureRemediation::getDelFlag, "0"));
        for (ClosureRemediation r : old) {
            r.setDelFlag("1");
            r.setUpdateBy(SecurityUtils.getCurrentUserId());
            r.setUpdateDate(LocalDateTime.now());
            remediationDao.updateById(r);
        }
        if (items != null) {
            for (Map<String, Object> m : items) {
                String content = str(m.get("content"));
                String ownerId = str(m.get("ownerId"));
                if (content == null || content.isBlank() || ownerId == null || ownerId.isBlank()) {
                    throw new ServiceException(422, "遗留项描述与责任人为必填");
                }
                ClosureRemediation r = new ClosureRemediation();
                r.setId(uuid());
                r.setCheckId(c.getId());
                r.setProjectId(projectId);
                r.setContent(content);
                r.setSourceItemKey(str(m.get("sourceItemKey")));
                r.setOwnerId(ownerId);
                r.setOwnerName(str(m.get("ownerName")));
                r.setStatus(ClosureRemediation.ST_DRAFT);
                r.setEscalatedFlag("0");
                r.setCreateBy(SecurityUtils.getCurrentUserId());
                r.setCreateDate(LocalDateTime.now());
                remediationDao.insert(r);
            }
        }
        return remediations(projectId);
    }

    /** 审批回调用：DRAFT 遗留项转 OPEN（+90 天）。package-private 供 PmClosureApprovalCallback 调用。 */
    public List<ClosureRemediation> activateRemediations(String checkId, LocalDateTime submitDate) {
        List<ClosureRemediation> list = remediationDao.selectList(
                new LambdaQueryWrapper<ClosureRemediation>()
                        .eq(ClosureRemediation::getCheckId, checkId)
                        .eq(ClosureRemediation::getStatus, ClosureRemediation.ST_DRAFT)
                        .eq(ClosureRemediation::getDelFlag, "0"));
        for (ClosureRemediation r : list) {
            r.setStatus(ClosureRemediation.ST_OPEN);
            r.setDueDate((submitDate != null ? submitDate : LocalDateTime.now())
                    .plusDays(REMEDIATION_DAYS));
            r.setUpdateBy("SYSTEM");
            r.setUpdateDate(LocalDateTime.now());
            remediationDao.updateById(r);
        }
        return list;
    }

    public ClosureCheck checkOf(String projectId) {
        return checkDao.selectOne(new LambdaQueryWrapper<ClosureCheck>()
                .eq(ClosureCheck::getProjectId, projectId)
                .eq(ClosureCheck::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    // ================================================================
    // 内部工具
    // ================================================================

    private void insertFixedItems(String checkId, Map<String, Object> ev) {
        Object[][] defs = {
                {ClosureCheckItem.K_WBS, "WBS 完成度=100%", "1", "1"},
                {ClosureCheckItem.K_MS, "全部里程碑审批通过", "1", "1"},
                {ClosureCheckItem.K_ACCEPT, "交付物验收报告已签字", "1", "0"},
                {ClosureCheckItem.K_COST, "成本已归集且偏差在容差内（|dev|≤5%）", "1", "1"},
                {ClosureCheckItem.K_IP, "IP 归属确认单完整", "1", "1"},
                {ClosureCheckItem.K_DOC, "项目文档已归档", "1", "0"},
        };
        int no = 1;
        for (Object[] d : defs) {
            ClosureCheckItem it = new ClosureCheckItem();
            it.setId(uuid());
            it.setCheckId(checkId);
            it.setLineNo(no++);
            it.setItemKey((String) d[0]);
            it.setItemName((String) d[1]);
            it.setMandatoryFlag((String) d[2]);
            it.setAutoFlag((String) d[3]);
            if ("1".equals(d[3])) {
                applyEvidence(it, ev);
            } else {
                it.setResult(ClosureCheckItem.R_PENDING);
            }
            it.setCreateBy(SecurityUtils.getCurrentUserId());
            it.setCreateDate(LocalDateTime.now());
            itemDao.insert(it);
        }
    }

    private void applyEvidence(ClosureCheckItem it, Map<String, Object> ev) {
        String key = it.getItemKey();
        boolean pass;
        String json;
        switch (key) {
            case ClosureCheckItem.K_WBS -> {
                BigDecimal rate = (BigDecimal) ev.get("doneRate");
                pass = rate != null && rate.compareTo(BigDecimal.valueOf(100)) >= 0;
                json = String.format("{\"doneRate\":%s,\"donePhases\":%s,\"totalPhases\":%s}",
                        rate, ev.get("donePhases"), ev.get("totalPhases"));
            }
            case ClosureCheckItem.K_MS -> {
                Long open = (Long) ev.get("openMs");
                pass = open != null && open == 0L;
                json = String.format("{\"openMs\":%s}", open);
            }
            case ClosureCheckItem.K_COST -> {
                BigDecimal dev = (BigDecimal) ev.get("deviation");
                pass = dev != null && dev.abs().compareTo(DEVIATION_TOLERANCE) <= 0;
                json = String.format("{\"collectTotal\":%s,\"baselineAmt\":%s,\"deviation\":%s}",
                        ev.get("collectTotal"), ev.get("baselineAmt"), dev);
            }
            case ClosureCheckItem.K_IP -> {
                Integer miss = (Integer) ev.get("missingIp");
                pass = miss != null && miss == 0;
                json = String.format("{\"missingIp\":%s}", miss);
            }
            default -> {
                pass = false;
                json = "{}";
            }
        }
        it.setResult(pass ? ClosureCheckItem.R_PASS : ClosureCheckItem.R_FAIL);
        it.setEvidenceJson(json);
        it.setEvidenceAt(LocalDateTime.now());
    }

    private List<ClosureCheckItem> itemsOf(String checkId) {
        return itemDao.selectList(new LambdaQueryWrapper<ClosureCheckItem>()
                .eq(ClosureCheckItem::getCheckId, checkId)
                .eq(ClosureCheckItem::getDelFlag, "0")
                .orderByAsc(ClosureCheckItem::getLineNo));
    }

    private ClosureCheck requireCheck(String projectId) {
        ClosureCheck c = checkOf(projectId);
        if (c == null) {
            throw new ServiceException(404, "结项检查单不存在，请先发起结项");
        }
        return c;
    }

    private PmProject requireProject(String projectId) {
        PmProject p = projectDao.selectById(projectId);
        if (p == null) {
            throw new ServiceException(404, "项目不存在：" + projectId);
        }
        return p;
    }

    private Map<String, Object> settlementOf(String projectId) {
        ClosureSettlement s = settlementDao.selectOne(new LambdaQueryWrapper<ClosureSettlement>()
                .eq(ClosureSettlement::getProjectId, projectId)
                .eq(ClosureSettlement::getDelFlag, "0")
                .last("LIMIT 1"));
        if (s == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("collectTotal", s.getCollectTotal());
        m.put("baselineAmt", s.getBaselineAmt());
        m.put("deviationRate", s.getDeviationRate());
        m.put("unfrozenFlag", s.getUnfrozenFlag());
        m.put("confirmBy", s.getConfirmBy());
        m.put("confirmDate", s.getConfirmDate());
        return m;
    }

    private void requireAttachments(String bizId, String message) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_attachment WHERE BIZ_TYPE=? AND BIZ_ID=? AND DEL_FLAG='0'",
                Long.class, ATTACH_BIZ, bizId);
        if (n == null || n == 0) {
            throw new ServiceException(422, message);
        }
    }

    private void requireProjectReport(String projectId) {
        requireAttachments(projectId, "请先上传《项目总结报告》附件（BR-4.8-30）");
    }

    private int nextLineNo(String checkId) {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(LINE_NO),0) FROM erp_pm_closure_check_item WHERE CHECK_ID=?",
                Long.class, checkId);
        return (max == null ? 0 : max.intValue()) + 1;
    }

    private Map<String, Object> itemMap(ClosureCheckItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("lineNo", it.getLineNo());
        m.put("itemKey", it.getItemKey());
        m.put("itemName", it.getItemName());
        m.put("mandatoryFlag", it.getMandatoryFlag());
        m.put("autoFlag", it.getAutoFlag());
        m.put("result", it.getResult());
        m.put("evidenceJson", it.getEvidenceJson());
        m.put("evidenceAt", it.getEvidenceAt());
        m.put("attachIds", it.getAttachIds());
        m.put("confirmBy", it.getConfirmBy());
        m.put("confirmDate", it.getConfirmDate());
        m.put("remark", it.getRemark());
        return m;
    }

    private Map<String, Object> remediationMap(ClosureRemediation r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("content", r.getContent());
        m.put("ownerId", r.getOwnerId());
        m.put("ownerName", r.getOwnerName());
        m.put("dueDate", r.getDueDate());
        m.put("status", r.getStatus());
        m.put("escalatedFlag", r.getEscalatedFlag());
        m.put("closeNote", r.getCloseNote());
        return m;
    }

    /** 服务层二次校验（SecurityConfig 之外的兜底） */
    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String role : allowed) {
            if (userRoles.stream().anyMatch(r -> role.equalsIgnoreCase(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权" + action);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
