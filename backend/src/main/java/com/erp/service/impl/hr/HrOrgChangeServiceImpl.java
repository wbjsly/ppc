package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrGradeDao;
import com.erp.dao.hr.HrOrgChangeDao;
import com.erp.dao.hr.HrPositionDao;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrGrade;
import com.erp.entity.hr.HrOrgChange;
import com.erp.entity.hr.HrPosition;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.hr.HrOrgChangeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 组织调整单（M10-2 design D3）：合并/主管变更/带宽变更统一 ApprovalEngine（HR 专员→HR 经理）。
 * 合并级联在审批通过回调执行（幂等：源部门已停用即跳过）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrOrgChangeServiceImpl implements HrOrgChangeService {

    public static final String BIZ_TYPE = "HrOrgChange";

    private final HrOrgChangeDao changeDao;
    private final HrDeptDao deptDao;
    private final HrEmployeeDao empDao;
    private final HrPositionDao posDao;
    private final HrGradeDao gradeDao;
    private final ApprovalEngine approvalEngine;
    private final SysParamService sysParamService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ================================================================
    // 1. 三型调整单创建
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createMerge(Map<String, Object> payload) {
        String sourceId = str(payload.get("sourceId"));
        String targetId = str(payload.get("targetId"));
        require(sourceId, "源部门");
        require(targetId, "目标部门");
        HrDept source = requireDept(sourceId);
        HrDept target = requireDept(targetId);
        if (sourceId.equals(targetId)) {
            throw new ServiceException(422, "源部门与目标部门不可相同");
        }
        // 非法方向：源为目标祖先（合并后目标的祖先链会指向已停用部门）
        if (isAncestor(sourceId, targetId)) {
            throw new ServiceException(422, "非法合并方向：源部门是目标部门的上级（L1 阻断）");
        }
        // 防重复：源存在未完成合并单
        long pending = changeDao.selectCount(new LambdaQueryWrapper<HrOrgChange>()
                .eq(HrOrgChange::getSourceId, sourceId)
                .eq(HrOrgChange::getChgType, HrOrgChange.TYPE_MERGE)
                .in(HrOrgChange::getStatus, HrOrgChange.ST_APPROVING, HrOrgChange.ST_DRAFT));
        if (pending > 0) {
            throw new ServiceException(422, "源部门存在进行中的合并调整单（防重复）");
        }
        return submit(HrOrgChange.TYPE_MERGE, sourceId, targetId, payload,
                "部门合并：" + source.getName() + " → " + target.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createManagerChange(String deptId, String newManagerEmpId) {
        HrDept dept = requireDept(deptId);
        HrEmployee mgr = newManagerEmpId == null || newManagerEmpId.isBlank()
                ? null : empDao.selectById(newManagerEmpId);
        if (newManagerEmpId != null && !newManagerEmpId.isBlank()) {
            if (mgr == null) {
                throw new ServiceException(404, "主管员工不存在：" + newManagerEmpId);
            }
            if (!"REGULAR".equals(mgr.getEmpStatus()) && !"PROBATION".equals(mgr.getEmpStatus())) {
                throw new ServiceException(422, "主管须为在职员工（当前状态：" + mgr.getEmpStatus() + "）");
            }
            // 须本部门在职员工（spec）
            if (deptId.equals(mgr.getDeptId()) == false) {
                throw new ServiceException(422, "主管须为本部门在职员工");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", Map.of("managerEmpId",
                dept.getManagerEmpId() == null ? "" : dept.getManagerEmpId()));
        payload.put("after", Map.of("managerEmpId",
                newManagerEmpId == null ? "" : newManagerEmpId));
        payload.put("managerName", mgr == null ? "" : mgr.getName());
        return submit(HrOrgChange.TYPE_DEPT_MANAGER, deptId, newManagerEmpId, payload,
                "部门主管变更：" + dept.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createBandChange(String gradeId, Map<String, Object> after) {
        HrGrade g = gradeDao.selectById(gradeId);
        if (g == null) {
            throw new ServiceException(404, "职级不存在：" + gradeId);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", Map.of("salMin", strip(g.getSalMin()), "salMax", strip(g.getSalMax())));
        payload.put("after", after);
        return submit(HrOrgChange.TYPE_GRADE_BAND, gradeId, null, payload,
                "职级带宽变更：" + g.getGradeNo() + " " + g.getName());
    }

    private Map<String, Object> submit(String type, String sourceId, String targetId,
                                       Map<String, Object> payload, String title) {
        HrOrgChange c = new HrOrgChange();
        c.setId(uuid());
        c.setChgNo(nextChgNo());
        c.setChgType(type);
        c.setSourceId(sourceId);
        c.setTargetId(targetId);
        c.setPayloadJson(writeJson(payload));
        c.setEffectiveDate(LocalDate.now());
        c.setStatus(HrOrgChange.ST_DRAFT);
        c.setCreateBy(currentUser());
        c.setCreateDate(LocalDateTime.now());
        changeDao.insert(c);
        // 两级审批：HR 专员 → HR 经理（升级角色 SysParam 可配）
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_HR", "HR 专员确认")));
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_HR_MGR", "HR 经理审批")));
        ApprovalInstance inst = approvalEngine.submit(BIZ_TYPE, c.getId(), title,
                sysParamService.getValue("HR_MGR_ROLE"), chain);
        c.setApprId(inst.getId());
        c.setStatus(HrOrgChange.ST_APPROVING);
        c.setUpdateBy(currentUser());
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.getId());
        out.put("chgNo", c.getChgNo());
        out.put("apprId", inst.getId());
        return out;
    }

    // ================================================================
    // 2. 审批通过执行（回调调用；幂等）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void execute(String changeId) {
        HrOrgChange c = changeDao.selectById(changeId);
        if (c == null || HrOrgChange.ST_EFFECTED.equals(c.getStatus())) {
            return; // 幂等
        }
        switch (c.getChgType()) {
            case HrOrgChange.TYPE_MERGE -> executeMerge(c);
            case HrOrgChange.TYPE_DEPT_MANAGER -> executeManager(c);
            case HrOrgChange.TYPE_GRADE_BAND -> executeBand(c);
            default -> log.warn("unknown org change type: {}", c.getChgType());
        }
    }

    /** 合并级联承接（D3）：员工/岗位换绑 + 汇报上移 + 源停用 + 快照留痕。 */
    private void executeMerge(HrOrgChange c) {
        HrDept source = deptDao.selectById(c.getSourceId());
        if (source == null) {
            log.warn("merge source missing: {}", c.getSourceId());
            markEffect(c, null);
            return;
        }
        if ("0".equals(source.getStatus())) {
            // 幂等：源已停用（已执行过）
            markEffect(c, Map.of("skipped", true));
            return;
        }
        HrDept target = deptDao.selectById(c.getTargetId());
        if (target == null || !"1".equals(target.getStatus())) {
            throw new ServiceException(422, "目标部门不存在或已停用，无法承接");
        }
        LocalDateTime now = LocalDateTime.now();
        // ① 员工换绑（updateById 忽略 null——显式 set；列名字符串规避 SFunction 解析问题）
        int empMoved = empDao.update(null, new UpdateWrapper<HrEmployee>()
                .eq("DEPT_ID", c.getSourceId())
                .set("DEPT_ID", c.getTargetId())
                .set("UPDATE_BY", "org-merge")
                .set("UPDATE_DATE", now));
        // ② 岗位换绑
        int posMoved = posDao.update(null, new UpdateWrapper<HrPosition>()
                .eq("DEPT_ID", c.getSourceId())
                .set("DEPT_ID", c.getTargetId())
                .set("UPDATE_BY", "org-merge")
                .set("UPDATE_DATE", now));
        // ③ 汇报上移：指向源主管的员工改指目标主管（无目标主管则清空）
        String sourceMgr = source.getManagerEmpId();
        String targetMgr = target.getManagerEmpId();
        int reportMoved = 0;
        if (sourceMgr != null && !sourceMgr.isBlank()) {
            reportMoved = empDao.update(null, new UpdateWrapper<HrEmployee>()
                    .eq("REPORT_TO_EMP_ID", sourceMgr)
                    .set("REPORT_TO_EMP_ID", targetMgr)
                    .set("UPDATE_BY", "org-merge")
                    .set("UPDATE_DATE", now));
        }
        // ④ 子部门上移（源的直接子部门挂到目标下，避免悬挂树）
        int childMoved = deptDao.update(null, new UpdateWrapper<HrDept>()
                .eq("PARENT_ID", c.getSourceId())
                .set("PARENT_ID", c.getTargetId())
                .set("UPDATE_BY", "org-merge")
                .set("UPDATE_DATE", now));
        // ⑤ 源停用
        deptDao.update(null, new UpdateWrapper<HrDept>()
                .eq("ID", c.getSourceId())
                .set("STATUS", "0")
                .set("UPDATE_BY", "org-merge")
                .set("UPDATE_DATE", now));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("empMoved", empMoved);
        counts.put("posMoved", posMoved);
        counts.put("reportMoved", reportMoved);
        counts.put("childMoved", childMoved);
        markEffect(c, counts);
        log.info("merge {} effected: {} emp/{} pos/{} report/{} child",
                c.getChgNo(), empMoved, posMoved, reportMoved, childMoved);
    }

    private void executeManager(HrOrgChange c) {
        deptDao.update(null, new UpdateWrapper<HrDept>()
                .eq("ID", c.getSourceId())
                .set("MANAGER_EMP_ID", c.getTargetId())
                .set("MANAGER_APPROVAL_ID", c.getId())
                .set("UPDATE_BY", "org-change")
                .set("UPDATE_DATE", LocalDateTime.now()));
        markEffect(c, null);
    }

    private void executeBand(HrOrgChange c) {
        try {
            Map<String, Object> after = objectMapper.readValue(c.getPayloadJson(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            Object target = after.get("after");
            Map<String, Object> t = target instanceof Map
                    ? objectMapper.convertValue(target,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class))
                    : Map.of();
            BigDecimal min = t.get("salMin") == null ? null : new BigDecimal(String.valueOf(t.get("salMin")));
            BigDecimal max = t.get("salMax") == null ? null : new BigDecimal(String.valueOf(t.get("salMax")));
            HrGrade g = gradeDao.selectById(c.getSourceId());
            if (g != null) {
                if (min != null) {
                    g.setSalMin(min);
                }
                if (max != null) {
                    g.setSalMax(max);
                }
                g.setUpdateBy("org-change");
                g.setUpdateDate(LocalDateTime.now());
                gradeDao.updateById(g);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(422, "带宽快照解析失败：" + e.getMessage());
        }
        markEffect(c, null);
    }

    private void markEffect(HrOrgChange c, Map<String, Object> result) {
        if (result != null) {
            try {
                Map<String, Object> merged = c.getPayloadJson() == null
                        ? new LinkedHashMap<String, Object>()
                        : objectMapper.readValue(c.getPayloadJson(),
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                merged.put("result", result);
                c.setPayloadJson(writeJson(merged));
            } catch (Exception ignored) {
                // 快照合并失败不阻断执行
            }
        }
        c.setStatus(HrOrgChange.ST_EFFECTED);
        c.setExecutedAt(LocalDateTime.now());
        c.setUpdateBy("approval");
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(String changeId) {
        HrOrgChange c = changeDao.selectById(changeId);
        if (c == null || HrOrgChange.ST_EFFECTED.equals(c.getStatus())) {
            return;
        }
        c.setStatus(HrOrgChange.ST_REJECTED);
        c.setUpdateBy("approval");
        c.setUpdateDate(LocalDateTime.now());
        changeDao.updateById(c);
    }

    @Override
    public List<HrOrgChange> list(String chgType, String status) {
        LambdaQueryWrapper<HrOrgChange> qw = new LambdaQueryWrapper<HrOrgChange>()
                .eq(HrOrgChange::getDelFlag, "0")
                .orderByDesc(HrOrgChange::getCreateDate);
        if (chgType != null && !chgType.isBlank()) {
            qw.eq(HrOrgChange::getChgType, chgType);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrOrgChange::getStatus, status);
        }
        return changeDao.selectList(qw);
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 祖先链上溯（D2）：判定 deptId 是否为 ancestorOf 的祖先 */
    private boolean isAncestor(String ancestorId, String deptId) {
        String cur = deptId;
        int depth = 0;
        int max = depthLimit();
        while (cur != null && depth < max) {
            if (ancestorId.equals(cur)) {
                return true;
            }
            HrDept d = deptDao.selectById(cur);
            cur = d == null ? null : d.getParentId();
            depth++;
        }
        return false;
    }

    private int depthLimit() {
        String v = sysParamService.getValue("HR_REPORT_DEPTH_MAX");
        try {
            return v == null ? 10 : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 10;
        }
    }

    private HrDept requireDept(String deptId) {
        HrDept d = deptDao.selectById(deptId);
        if (d == null) {
            throw new ServiceException(404, "部门不存在：" + deptId);
        }
        return d;
    }

    private String nextChgNo() {
        String prefix = "OC" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        List<String> existing = changeDao.selectNosByPrefix(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                // 只取最后一个横杠后的流水（避免把日期数字 parse 进去导致溢出/重复键）
                String tail = no.substring(no.lastIndexOf('-') + 1).replaceAll("[^0-9]", "");
                if (!tail.isEmpty()) {
                    max = Math.max(max, Integer.parseInt(tail));
                }
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(422, "快照序列化失败");
        }
    }

    private static String strip(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
