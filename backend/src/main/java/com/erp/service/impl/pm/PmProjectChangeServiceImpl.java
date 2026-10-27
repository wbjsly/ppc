package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmBaselineVersionDao;
import com.erp.dao.pm.PmBudgetLineDao;
import com.erp.dao.pm.PmChangeWbsDao;
import com.erp.dao.pm.PmProjectChangeDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmBaselineVersion;
import com.erp.entity.pm.PmBudgetLine;
import com.erp.entity.pm.PmChangeWbs;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmProjectChange;
import com.erp.entity.pm.PmWbs;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.pm.PmProjectChangeService;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 项目变更申请与执行跟踪实现（add-project-change，design D2/D6）。
 * BIZ_TYPE=PmProjectChange；三级动态路由：进度≤5d且预算≤5% PMO / 进度>5d或预算5-10% PMO+财务双签 /
 * 预算>10%或涉合同 EXEC+PMO双签；第三档 L1 校验客户书面附件。涉合同=手动 OR >10% 强制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmProjectChangeServiceImpl implements PmProjectChangeService {

    /** 审批 BIZ_TYPE（PmChangeApprovalCallback 对齐） */
    public static final String BIZ_PROJECT_CHANGE = "PmProjectChange";

    private final PmProjectChangeDao changeDao;
    private final PmChangeWbsDao changeWbsDao;
    private final PmProjectDao projectDao;
    private final PmWbsDao wbsDao;
    private final PmBudgetLineDao budgetLineDao;
    private final PmBaselineVersionDao baselineVersionDao;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;
    private final PmClosureGuard closureGuard;

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload, boolean submit) {
        String projectId = str(payload.get("projectId"));
        PmProject p = requireProject(projectId);
        // 结项拦截（add-project-closure）：CLOSING/CLOSED 项目不可发起变更
        closureGuard.assertWritable(projectId, false);
        // 仅 ACTIVE（基线已锁定并启动）项目可发起变更
        if (!PmProject.ST_ACTIVE.equals(p.getStatus())) {
            throw new ServiceException(422, "仅执行中（ACTIVE）项目可发起变更");
        }
        String changeType = str(payload.get("changeType"));
        if (!isValidType(changeType)) {
            throw new ServiceException(422, "变更类型须为 SCOPE/SCHEDULE/BUDGET/RESOURCE/COMBO 五选一");
        }
        String reason = str(payload.get("reason"));
        if (!hasText(reason)) {
            throw new ServiceException(422, "变更原因必填");
        }
        String description = str(payload.get("description"));
        LocalDate effectiveDate = decDate(payload.get("effectiveDate"));
        if (effectiveDate == null) {
            throw new ServiceException(422, "期望生效日期必填");
        }
        // 倒签 L1（BR-4.8-32）
        if (effectiveDate.isBefore(LocalDate.now())) {
            throw new ServiceException(422, "倒签变更一律拒绝（BR-4.8-32）：期望生效日期早于今日");
        }
        // 受影响 WBS 集（提交时必填 L1；草稿可暂缺）
        List<String> wbsIds = new ArrayList<>();
        if (payload.get("wbsIds") instanceof List<?> list) {
            for (Object o : list) {
                String w = o == null ? null : String.valueOf(o);
                if (hasText(w)) {
                    wbsIds.add(w);
                }
            }
        }
        if (submit && wbsIds.isEmpty()) {
            throw new ServiceException(422, "变更须关联受影响的 WBS 元素（C-4.8-09）");
        }
        // 描述<20 字 L4 提示（不阻断）
        boolean descTooShort = hasText(description) && description.trim().length() < 20;

        boolean isNew = true;
        PmProjectChange c;
        String id = str(payload.get("id"));
        if (hasText(id)) {
            c = changeDao.selectById(id);
            if (c == null || "1".equals(c.getDelFlag())) {
                throw new ServiceException(422, "变更单不存在");
            }
            if (!PmProjectChange.ST_DRAFT.equals(c.getStatus())
                    && !PmProjectChange.ST_REJECTED.equals(c.getStatus())) {
                throw new ServiceException(422, "仅草稿/已驳回变更单可修改");
            }
            isNew = false;
        } else {
            c = new PmProjectChange();
            c.setId(uuid());
            c.setChangeNo(nextNo());
            c.setCreateBy(currentUser());
            c.setCreateDate(LocalDateTime.now());
        }
        c.setProjectId(projectId);
        c.setChangeType(changeType);
        c.setReason(reason);
        c.setDescription(description);
        c.setEffectiveDate(effectiveDate);
        c.setScopeImpact(str(payload.get("scopeImpact")));
        c.setScheduleImpactDays(decInt(payload.get("scheduleImpactDays")));
        c.setResourceImpact(str(payload.get("resourceImpact")));
        BigDecimal budgetAdd = dec(payload.get("budgetAdd"));
        c.setBudgetAdd(budgetAdd == null ? BigDecimal.ZERO : budgetAdd);
        c.setStatus(PmProjectChange.ST_DRAFT);
        // 涉合同：手动勾 OR >10% 强制（取或，服务端算）
        boolean manualContract = "1".equals(str(payload.get("isContractFlag")));
        c.setContractId(str(payload.get("contractId")));
        c.setContractNo(str(payload.get("contractNo")));
        c.setCustomerConfirmFlag("1".equals(str(payload.get("customerConfirmFlag"))) ? "1" : "0");

        Map<String, Object> out = new LinkedHashMap<>();
        if (isNew) {
            changeDao.insert(c);
        } else {
            c.setUpdateBy(currentUser());
            c.setUpdateDate(LocalDateTime.now());
            changeDao.updateById(c);
        }
        // 受影响 WBS 集：替换式保存
        for (PmChangeWbs old : changeWbsDao.selectList(new LambdaQueryWrapper<PmChangeWbs>()
                .eq(PmChangeWbs::getChangeId, c.getId()))) {
            changeWbsDao.deleteById(old.getId());
        }
        for (String wbsId : wbsIds) {
            PmChangeWbs cw = new PmChangeWbs();
            cw.setId(uuid());
            cw.setChangeId(c.getId());
            cw.setWbsId(wbsId);
            cw.setCreateBy(currentUser());
            cw.setCreateDate(LocalDateTime.now());
            changeWbsDao.insert(cw);
        }
        out.put("id", c.getId());
        out.put("changeNo", c.getChangeNo());
        out.put("status", c.getStatus());
        out.put("wbsCount", wbsIds.size());
        if (descTooShort) {
            out.put("l4Warning", "变更描述少于 20 字，建议补充变更细节（不阻断）");
        }
        if (submit) {
            out.putAll(submitForApproval(c, p, manualContract));
        }
        return out;
    }

    /** 提交审批：算占比、涉合同取或、三级路由（design D2） */
    private Map<String, Object> submitForApproval(PmProjectChange c, PmProject p, boolean manualContract) {
        BigDecimal baseline = baselineOf(c.getProjectId());
        BigDecimal budgetAdd = c.getBudgetAdd() == null ? BigDecimal.ZERO : c.getBudgetAdd();
        BigDecimal rate;
        if (baseline.signum() > 0) {
            rate = budgetAdd.multiply(new BigDecimal("100"))
                    .divide(baseline, 4, RoundingMode.HALF_UP);
        } else {
            rate = BigDecimal.ZERO;
        }
        c.setBudgetRate(rate);
        // 涉合同 = 手动 OR >10% 强制
        boolean isContract = manualContract || rate.compareTo(new BigDecimal("10")) > 0;
        c.setIsContractFlag(isContract ? "1" : "0");
        // 第三档 L1：客户书面附件必填
        int tier = routeTier(c.getScheduleImpactDays(), rate, isContract);
        if (tier == 3 && !"1".equals(c.getCustomerConfirmFlag())) {
            throw new ServiceException(422,
                    "第三档变更（预算影响>10% 或涉合同）须上传客户书面确认附件（C-4.8-10）");
        }
        // 影响分析缺失不可提交
        if (!hasText(c.getScopeImpact()) && c.getScheduleImpactDays() == null
                && !hasText(c.getResourceImpact()) && c.getBudgetAdd() == null) {
            throw new ServiceException(422, "影响分析未完成，不可提交审批（范围/进度/资源/预算至少填一项）");
        }
        List<List<ApprovalNodeSpec>> seqGroups = switch (tier) {
            case 3 -> List.of(List.of(ApprovalNodeSpec.sign("ROLE_EXEC", "分管领导审批"),
                    ApprovalNodeSpec.sign("ROLE_PMO", "PMO 审批")));
            case 2 -> List.of(List.of(ApprovalNodeSpec.sign("ROLE_PMO", "PMO 审批"),
                    ApprovalNodeSpec.sign("ROLE_FINANCE", "财务审批")));
            default -> List.of(List.of(ApprovalNodeSpec.sign("ROLE_PMO", "PMO 审批")));
        };
        String tierName = tier == 3 ? "分管领导+PMO 双签" : tier == 2 ? "PMO+财务 双签" : "PMO 单签";
        var inst = approvalEngine.submit(
                BIZ_PROJECT_CHANGE,
                c.getId(),
                "项目变更：" + p.getName() + "（" + c.getChangeNo() + "，预算影响 " + rate + "%）",
                null,
                seqGroups);
        c.setApprId(inst.getId());
        c.setStatus(PmProjectChange.ST_APPROVING);
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("budgetRate", rate);
        out.put("isContractFlag", c.getIsContractFlag());
        out.put("approvalTier", tier);
        out.put("approvalTierName", tierName);
        out.put("apprId", inst.getId());
        return out;
    }

    /** 三级路由判定（design D2）：3=EXEC+PMO / 2=PMO+FINANCE / 1=PMO */
    private static int routeTier(Integer scheduleDays, BigDecimal rate, boolean isContract) {
        if (isContract || rate.compareTo(new BigDecimal("10")) > 0) {
            return 3;
        }
        if ((scheduleDays != null && scheduleDays > 5)
                || rate.compareTo(new BigDecimal("5")) > 0) {
            return 2;
        }
        return 1;
    }

    @Override
    public List<Map<String, Object>> list(String projectId, String status, String changeType) {
        LambdaQueryWrapper<PmProjectChange> qw = new LambdaQueryWrapper<PmProjectChange>()
                .eq(PmProjectChange::getDelFlag, "0")
                .orderByDesc(PmProjectChange::getCreateDate);
        if (hasText(projectId)) {
            qw.eq(PmProjectChange::getProjectId, projectId);
        }
        if (hasText(status)) {
            qw.eq(PmProjectChange::getStatus, status);
        }
        if (hasText(changeType)) {
            qw.eq(PmProjectChange::getChangeType, changeType);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmProjectChange c : changeDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("change", c);
            PmProject p = projectDao.selectById(c.getProjectId());
            row.put("projectName", p == null ? null : p.getName());
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        PmProjectChange c = requireChange(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("change", c);
        out.put("project", projectDao.selectById(c.getProjectId()));
        List<Map<String, Object>> wbsRows = new ArrayList<>();
        for (PmChangeWbs cw : changeWbsDao.selectList(new LambdaQueryWrapper<PmChangeWbs>()
                .eq(PmChangeWbs::getChangeId, id))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wbsId", cw.getWbsId());
            PmWbs w = wbsDao.selectById(cw.getWbsId());
            row.put("wbsName", w == null ? null : w.getName());
            wbsRows.add(row);
        }
        out.put("wbsSet", wbsRows);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> advance(String id, Map<String, Object> payload) {
        PmProjectChange c = requireChange(id);
        if (!PmProjectChange.ST_EXECUTING.equals(c.getStatus())) {
            throw new ServiceException(422, "仅执行中（EXECUTING）变更可推进为已完成");
        }
        // 单据级校验：仅变更创建人（PM）可推进
        String me = currentUser();
        if (!me.equals(c.getCreateBy())) {
            throw new ServiceException(403, "仅变更发起人（项目经理）可确认变更落地");
        }
        c.setStatus(PmProjectChange.ST_DONE);
        c.setPmOpinion(str(payload.get("opinion")));
        c.setUpdateBy(me);
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("status", c.getStatus());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> verify(String id, Map<String, Object> payload) {
        PmProjectChange c = requireChange(id);
        if (!PmProjectChange.ST_DONE.equals(c.getStatus())) {
            throw new ServiceException(422, "仅已完成（DONE）变更可核验为已验证");
        }
        // 单据级校验：仅项目 PMO 可核验
        PmProject p = projectDao.selectById(c.getProjectId());
        String me = currentUser();
        if (p != null && hasText(p.getPmoUserId()) && !me.equals(p.getPmoUserId())) {
            throw new ServiceException(403, "仅项目 PMO 可核验变更");
        }
        BigDecimal deviation = dec(payload.get("verifyDeviation"));
        c.setVerifyOpinion(str(payload.get("opinion")));
        c.setVerifyDeviation(deviation);
        c.setStatus(PmProjectChange.ST_VERIFIED);
        c.setUpdateBy(me);
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("status", c.getStatus());
        // 偏差>50% → L4 提示 + 升级通知 PMO（design D6）
        if (deviation != null && deviation.compareTo(new BigDecimal("50")) > 0) {
            out.put("l4Warning", "实际偏差 " + deviation + "% 超预估 50%，请项目经理复盘并升级 PMO 关注");
            noticeService.push("ROLE_PMO", null,
                    "项目变更偏差过大：" + c.getChangeNo(),
                    "变更 " + c.getChangeNo() + " 实际偏差 " + deviation
                            + "% 超预估 50%，请复盘偏差原因并纳入项目经验库。",
                    BIZ_PROJECT_CHANGE, c.getId());
        }
        return out;
    }

    @Override
    public Map<String, Object> stats(String projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        LambdaQueryWrapper<PmProjectChange> qw = new LambdaQueryWrapper<PmProjectChange>()
                .eq(PmProjectChange::getDelFlag, "0");
        if (hasText(projectId)) {
            qw.eq(PmProjectChange::getProjectId, projectId);
        }
        List<PmProjectChange> all = changeDao.selectList(qw);
        Map<String, Long> byType = new LinkedHashMap<>();
        Map<String, Long> byReason = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long contractCount = 0;
        for (PmProjectChange c : all) {
            byType.merge(nvl(c.getChangeType()), 1L, Long::sum);
            byReason.merge(nvl(c.getReason()), 1L, Long::sum);
            byStatus.merge(nvl(c.getStatus()), 1L, Long::sum);
            if ("1".equals(c.getIsContractFlag())) {
                contractCount++;
            }
        }
        out.put("total", (long) all.size());
        out.put("byType", byType);
        out.put("byReason", byReason);
        out.put("byStatus", byStatus);
        out.put("contractCount", contractCount);
        return out;
    }

    // ---- helpers ----

    private BigDecimal baselineOf(String projectId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (PmBudgetLine l : budgetLineDao.selectList(new LambdaQueryWrapper<PmBudgetLine>()
                .eq(PmBudgetLine::getProjectId, projectId)
                .eq(PmBudgetLine::getDelFlag, "0"))) {
            sum = sum.add(nz(l.getLabor())).add(nz(l.getMaterial())).add(nz(l.getSubcontract()))
                    .add(nz(l.getTravel())).add(nz(l.getOther())).add(nz(l.getIndirect()));
        }
        if (sum.signum() <= 0) {
            PmProject p = projectDao.selectById(projectId);
            sum = p == null || p.getBaselineAmount() == null ? BigDecimal.ZERO : p.getBaselineAmount();
        }
        return sum;
    }

    private static boolean isValidType(String t) {
        return PmProjectChange.TYPE_SCOPE.equals(t) || PmProjectChange.TYPE_SCHEDULE.equals(t)
                || PmProjectChange.TYPE_BUDGET.equals(t) || PmProjectChange.TYPE_RESOURCE.equals(t)
                || PmProjectChange.TYPE_COMBO.equals(t);
    }

    private PmProjectChange requireChange(String id) {
        PmProjectChange c = changeDao.selectById(id);
        if (c == null || "1".equals(c.getDelFlag())) {
            throw new ServiceException(422, "变更单不存在");
        }
        return c;
    }

    private PmProject requireProject(String projectId) {
        if (!hasText(projectId)) {
            throw new ServiceException(422, "项目 ID 必填");
        }
        PmProject p = projectDao.selectById(projectId);
        if (p == null || "1".equals(p.getDelFlag())) {
            throw new ServiceException(404, "项目不存在");
        }
        return p;
    }

    private String nextNo() {
        String prefix = "PC" + LocalDate.now().toString().replace("-", "");
        Long n = changeDao.selectCount(new LambdaQueryWrapper<PmProjectChange>()
                .likeRight(PmProjectChange::getChangeNo, prefix));
        return prefix + String.format("%03d", (n == null ? 0 : n) + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static LocalDate decDate(Object o) {
        String s = str(o);
        if (!hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer decInt(Object o) {
        BigDecimal d = dec(o);
        return d == null ? null : d.intValue();
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String currentUser() {
        try {
            return com.erp.util.SecurityUtils.getCurrentUserId();
        } catch (Exception e) {
            return "system";
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
