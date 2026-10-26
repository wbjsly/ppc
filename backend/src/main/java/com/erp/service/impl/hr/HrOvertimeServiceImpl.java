package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrOvertimeApplyDao;
import com.erp.dao.hr.HrOvertimeConfirmDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrOvertimeApply;
import com.erp.entity.hr.HrOvertimeConfirm;
import com.erp.entity.hr.HrRemind;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrOvertimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 加班申请实现（FR-4.7-6-1/2：36h L1 拦截/紧急补单窗口/主管审批）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrOvertimeServiceImpl implements HrOvertimeService {

    private final HrOvertimeApplyDao applyDao;
    private final HrOvertimeConfirmDao confirmDao;
    private final HrEmployeeDao empDao;
    private final HrDeptDao deptDao;
    private final HrRemindDao remindDao;
    private final SysParamService sysParamService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrOvertimeApply submit(Map<String, Object> payload) {
        String empId = str(payload.get("empId"));
        HrEmployee e = empId == null ? null : empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工不存在：" + empId);
        }
        LocalDate otDate = date(payload.get("otDate"));
        if (otDate == null) {
            throw new ServiceException(422, "加班日期必填");
        }
        String start = str(payload.get("startTime"));
        String end = str(payload.get("endTime"));
        require(start, "开始时间");
        require(end, "结束时间");
        BigDecimal hours = dec(payload.get("hours"));
        if (hours == null || hours.signum() <= 0) {
            hours = computeHours(start, end);
        }
        if (hours.signum() <= 0) {
            throw new ServiceException(422, "加班时长必须大于 0");
        }
        String reason = str(payload.get("reason"));
        require(reason, "加班事由"); // 未关联事由退回补充
        boolean emergency = "1".equals(String.valueOf(payload.get("isEmergency")));
        String emergencyNote = str(payload.get("emergencyNote"));
        if (emergency) {
            // 紧急补单：事后 1 工作日内 + 主管书面说明必填
            if (otDate.isAfter(LocalDate.now())) {
                throw new ServiceException(422, "补单日期不可晚于今日");
            }
            long workDays = workDaysBetween(otDate, LocalDate.now());
            if (workDays > 1) {
                throw new ServiceException(422, "紧急补单须在加班后 1 个工作日内提交");
            }
            require(emergencyNote, "主管书面说明（补单必填）");
        } else if (otDate.isBefore(LocalDate.now())) {
            // 事前申请：已过日期且非补单 → 提示走补单
            throw new ServiceException(422, "加班已发生，请勾选紧急补单并附主管书面说明");
        }
        // 36h 月上限 L1（已申请 + 已确认）——仅总经理特批通道可放行（BR-4.7-04）
        int cap = intParam("OT_MONTHLY_CAP_HOURS", 36);
        BigDecimal monthHours = monthHours(e.getId(), otDate);
        boolean special = "1".equals(String.valueOf(payload.get("isSpecial")));
        String specialReason = str(payload.get("specialReason"));
        boolean overCap = monthHours.add(hours).compareTo(BigDecimal.valueOf(cap)) > 0;
        if (overCap && !special) {
            throw new ServiceException(422, "月度加班将超上限 " + cap + " 小时（当前累计 "
                    + monthHours.stripTrailingZeros().toPlainString()
                    + "，L1 硬阻断 FR-4.7-6-1；可勾选申请总经理特批 BR-4.7-04）");
        }
        if (overCap && special) {
            require(specialReason, "特批超限原因（勾选特批必填）");
        }
        HrOvertimeApply a = new HrOvertimeApply();
        a.setId(uuid());
        a.setOtNo(nextNo(otDate));
        a.setEmpId(e.getId());
        a.setEmpNo(e.getEmpNo());
        a.setEmpName(e.getName());
        a.setOtDate(otDate);
        a.setStartTime(start);
        a.setEndTime(end);
        a.setHours(hours);
        a.setReason(reason);
        a.setProjectRef(str(payload.get("projectRef")));
        a.setIsEmergency(emergency ? "1" : "0");
        a.setEmergencyNote(emergencyNote);
        a.setIsSpecial(special ? "1" : "0");
        a.setSpecialReason(special ? specialReason : null);
        // 特批在途计入月累计，防连续勾特批无限叠加（GM 驳回转 REJECTED 自动释放）
        a.setStatus(overCap ? HrOvertimeApply.ST_GM_SPECIAL : HrOvertimeApply.ST_DRAFT);
        a.setMonthHoursSnapshot(monthHours);
        a.setCreateBy(currentUser());
        a.setCreateDate(LocalDateTime.now());
        applyDao.insert(a);
        log.info("overtime apply submitted: {} {} {}h", a.getOtNo(), e.getEmpNo(), hours);
        return a;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrOvertimeApply approve(String applyId, Map<String, Object> payload) {
        HrOvertimeApply a = require(applyId);
        boolean approve = !"false".equals(String.valueOf(payload.get("approve")));
        String opinion = str(payload.get("opinion"));
        if (HrOvertimeApply.ST_GM_SPECIAL.equals(a.getStatus())) {
            // 总经理特批节点：仅 ROLE_GM 可批（BR-4.7-04 留痕）
            if (!hasRole("ROLE_GM")) {
                throw new ServiceException(403, "总经理特批单仅 ROLE_GM 可审批");
            }
            a.setStatus(approve ? HrOvertimeApply.ST_APPROVED : HrOvertimeApply.ST_REJECTED);
        } else {
            if (!HrOvertimeApply.ST_DRAFT.equals(a.getStatus())
                    && !HrOvertimeApply.ST_APPROVING.equals(a.getStatus())) {
                throw new ServiceException(422, "申请状态不可审批（当前：" + a.getStatus() + "）");
            }
            a.setStatus(approve ? HrOvertimeApply.ST_APPROVED : HrOvertimeApply.ST_REJECTED);
        }
        a.setApprId(currentUser());
        a.setApprOpinion(opinion);
        a.setUpdateBy(currentUser());
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("overtime apply {} {}", a.getOtNo(), approve ? "approved" : "rejected");
        return a;
    }

    @Override
    public Map<String, Object> approvalView(String applyId) {
        HrOvertimeApply a = require(applyId);
        BigDecimal monthHours = monthHours(a.getEmpId(), a.getOtDate());
        // 部门预算占用率 = 已确认小时 /（编制 × OT_BUDGET_HOURS_PER_HEAD）
        HrEmployee e = empDao.selectById(a.getEmpId());
        BigDecimal budgetUsage = null;
        if (e != null && e.getDeptId() != null) {
            HrDept dept = deptDao.selectById(e.getDeptId());
            if (dept != null) {
                long headcount = empDao.selectCount(new LambdaQueryWrapper<HrEmployee>()
                        .eq(HrEmployee::getDeptId, e.getDeptId())
                        .eq(HrEmployee::getDelFlag, "0")
                        .in(HrEmployee::getEmpStatus, "PROBATION", "REGULAR"));
                BigDecimal perHead = dec(sysParamService.getValue("OT_BUDGET_HOURS_PER_HEAD"));
                if (perHead == null) {
                    perHead = BigDecimal.valueOf(40);
                }
                BigDecimal budget = perHead.multiply(BigDecimal.valueOf(headcount));
                BigDecimal used = monthDeptConfirmedHours(e.getDeptId(), a.getOtDate());
                budgetUsage = budget.signum() == 0 ? BigDecimal.ZERO
                        : used.multiply(BigDecimal.valueOf(100)).divide(budget, 2, RoundingMode.HALF_UP);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", a);
        out.put("monthHours", monthHours);
        out.put("budgetUsagePct", budgetUsage);
        return out;
    }

    @Override
    public List<HrOvertimeApply> list(String empId, String status) {
        LambdaQueryWrapper<HrOvertimeApply> qw = new LambdaQueryWrapper<HrOvertimeApply>()
                .eq(HrOvertimeApply::getDelFlag, "0")
                .orderByDesc(HrOvertimeApply::getOtDate);
        if (empId != null && !empId.isBlank()) {
            qw.eq(HrOvertimeApply::getEmpId, empId);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrOvertimeApply::getStatus, status);
        }
        return applyDao.selectList(qw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int approvalRemindScan() {
        int days = intParam("OT_APPROVE_REMIND_DAYS", 2);
        LocalDate today = LocalDate.now();
        // 超时未审批（DRAFT/APPROVING/GM_SPECIAL），按部门主管×日聚合一条，规避 hr_remind 同对象同日 UK
        List<HrOvertimeApply> pending = applyDao.selectList(new LambdaQueryWrapper<HrOvertimeApply>()
                .eq(HrOvertimeApply::getDelFlag, "0")
                .in(HrOvertimeApply::getStatus,
                        HrOvertimeApply.ST_DRAFT, HrOvertimeApply.ST_APPROVING, HrOvertimeApply.ST_GM_SPECIAL)
                .le(HrOvertimeApply::getCreateDate, java.sql.Timestamp.valueOf(today.atStartOfDay())));
        Map<String, List<HrOvertimeApply>> byManager = new LinkedHashMap<>();
        for (HrOvertimeApply a : pending) {
            // 工作日口径：跳周末，法定假调休不感知（宁少催不误催，留桩）
            if (workDaysBetween(a.getCreateDate().toLocalDate(), today) < days) {
                continue;
            }
            HrEmployee e = empDao.selectById(a.getEmpId());
            HrDept dept = e == null || e.getDeptId() == null ? null : deptDao.selectById(e.getDeptId());
            String mgrId = dept != null ? dept.getManagerEmpId() : null;
            if (mgrId == null || mgrId.isBlank()) {
                mgrId = "HR"; // 无部门主管兜底归口 HR
            }
            byManager.computeIfAbsent(mgrId, k -> new java.util.ArrayList<>()).add(a);
        }
        int reminded = 0;
        for (Map.Entry<String, List<HrOvertimeApply>> en : byManager.entrySet()) {
            String mgrId = en.getKey();
            List<HrOvertimeApply> applies = en.getValue();
            boolean exists = remindDao.exists(new LambdaQueryWrapper<HrRemind>()
                    .eq(HrRemind::getRemindType, HrRemind.TYPE_OT_APPROVE_OVERDUE)
                    .eq(HrRemind::getEmpId, mgrId)
                    .eq(HrRemind::getRemindDate, today));
            if (exists) {
                continue; // 同主管同日不重复轰炸
            }
            StringBuilder detail = new StringBuilder("加班申请超时未审批（")
                    .append(days).append(" 个工作日）：");
            for (HrOvertimeApply a : applies) {
                detail.append(a.getOtNo()).append("(").append(a.getEmpName()).append(") ");
            }
            HrRemind r = new HrRemind();
            r.setId(uuid());
            r.setRemindType(HrRemind.TYPE_OT_APPROVE_OVERDUE);
            r.setEmpId(mgrId);
            HrEmployee mgr = "HR".equals(mgrId) ? null : empDao.selectById(mgrId);
            r.setEmpName(mgr == null ? "HR 专员" : mgr.getName());
            r.setRemindDate(today);
            r.setDetail(detail.toString().trim());
            r.setCreateBy("scan");
            r.setCreateDate(LocalDateTime.now());
            remindDao.insert(r);
            reminded++;
        }
        return reminded;
    }

    @Override
    public List<HrRemind> approvalReminders() {
        return remindDao.selectList(new LambdaQueryWrapper<HrRemind>()
                .eq(HrRemind::getRemindType, HrRemind.TYPE_OT_APPROVE_OVERDUE)
                .ge(HrRemind::getRemindDate, LocalDate.now().minusDays(30))
                .orderByDesc(HrRemind::getRemindDate)
                .last("LIMIT 100"));
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 当月累计 = 未驳回申请 hours + 已确认 validHours。 */
    private BigDecimal monthHours(String empId, LocalDate otDate) {
        LocalDate start = otDate.withDayOfMonth(1);
        LocalDate end = otDate.withDayOfMonth(otDate.lengthOfMonth());
        BigDecimal applied = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(HOURS),0) FROM hr_overtime_apply WHERE EMP_ID=? AND DEL_FLAG='0' "
                        + "AND STATUS IN ('DRAFT','APPROVING','GM_SPECIAL','APPROVED','CONFIRMED') "
                        + "AND OT_DATE BETWEEN ? AND ?", BigDecimal.class, empId, start, end));
        BigDecimal confirmed = nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(VALID_HOURS),0) FROM hr_overtime_confirm WHERE EMP_ID=? "
                        + "AND DEL_FLAG='0' AND OT_DATE BETWEEN ? AND ?", BigDecimal.class, empId, start, end));
        return applied.add(confirmed);
    }

    private BigDecimal monthDeptConfirmedHours(String deptId, LocalDate otDate) {
        LocalDate start = otDate.withDayOfMonth(1);
        LocalDate end = otDate.withDayOfMonth(otDate.lengthOfMonth());
        return nvl(jdbc.queryForObject(
                "SELECT COALESCE(SUM(c.VALID_HOURS),0) FROM hr_overtime_confirm c "
                        + "JOIN hr_employee e ON e.ID=c.EMP_ID WHERE e.DEPT_ID=? AND c.DEL_FLAG='0' "
                        + "AND c.OT_DATE BETWEEN ? AND ?", BigDecimal.class, deptId, start, end));
    }

    private static BigDecimal computeHours(String start, String end) {
        try {
            java.time.LocalTime st = java.time.LocalTime.parse(start, DateTimeFormatter.ofPattern("HH:mm"));
            java.time.LocalTime et = java.time.LocalTime.parse(end, DateTimeFormatter.ofPattern("HH:mm"));
            long mins = et.isAfter(st)
                    ? java.time.Duration.between(st, et).toMinutes()
                    : java.time.Duration.between(st, et.plusHours(24)).toMinutes();
            return BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new ServiceException(422, "时间格式非法（HH:mm）");
        }
    }

    private long workDaysBetween(LocalDate from, LocalDate to) {
        long days = 0;
        LocalDate d = from.plusDays(1);
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek().getValue() < 6) {
                days++;
            }
            d = d.plusDays(1);
        }
        return days;
    }

    private String nextNo(LocalDate date) {
        String prefix = "OT" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        List<String> existing = applyDao.selectNosByPrefix(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(no.lastIndexOf('-') + 1)));
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private HrOvertimeApply require(String id) {
        HrOvertimeApply a = applyDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "加班申请不存在：" + id);
        }
        return a;
    }

    private int intParam(String key, int def) {
        String v = sysParamService.getValue(key);
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式非法：" + o);
        }
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "数值格式非法：" + o);
        }
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

    private static boolean hasRole(String role) {
        try {
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            return auth != null && auth.getAuthorities() != null
                    && auth.getAuthorities().stream().anyMatch(g -> role.equals(g.getAuthority()));
        } catch (Exception e) {
            return false;
        }
    }
}
