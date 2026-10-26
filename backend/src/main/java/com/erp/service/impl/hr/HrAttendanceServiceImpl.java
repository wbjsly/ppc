package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrAttendanceExceptionDao;
import com.erp.dao.hr.HrAttendanceMonthLockDao;
import com.erp.dao.hr.HrAttendanceOpLogDao;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.entity.hr.HrAttendanceException;
import com.erp.entity.hr.HrAttendanceMonthLock;
import com.erp.entity.hr.HrAttendanceOpLog;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrAttendanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 考勤异常状态机批量操作（D3：不复用 ApprovalEngine，操作日志留痕）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrAttendanceServiceImpl implements HrAttendanceService {

    private final HrAttendanceExceptionDao excDao;
    private final HrAttendanceOpLogDao opLogDao;
    private final HrAttendanceMonthLockDao lockDao;
    private final HrEmployeeDao empDao;
    private final HrDeptDao deptDao;
    private final SysParamService sysParamService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<HrAttendanceException> list(String status, String empId, String fromDate, String toDate) {
        LambdaQueryWrapper<HrAttendanceException> qw = new LambdaQueryWrapper<HrAttendanceException>()
                .eq(HrAttendanceException::getDelFlag, "0")
                .orderByDesc(HrAttendanceException::getExcDate);
        if (status != null && !status.isBlank()) {
            qw.eq(HrAttendanceException::getStatus, status);
        }
        if (empId != null && !empId.isBlank()) {
            qw.eq(HrAttendanceException::getEmpId, empId);
        }
        if (fromDate != null && !fromDate.isBlank()) {
            qw.ge(HrAttendanceException::getExcDate, LocalDate.parse(fromDate));
        }
        if (toDate != null && !toDate.isBlank()) {
            qw.le(HrAttendanceException::getExcDate, LocalDate.parse(toDate));
        }
        return excDao.selectList(qw);
    }

    // ================================================================
    // 批量申诉
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchAppeal(Map<String, Object> payload) {
        List<String> ids = strList(payload.get("ids"));
        String reason = str(payload.get("reason"));
        String evidence = str(payload.get("evidenceRef"));
        if (ids.isEmpty()) {
            throw new ServiceException(422, "请选择异常记录");
        }
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(422, "申诉原因必填");
        }
        int appealDays = intParam("ATTEND_APPEAL_DAYS", 5);
        LocalDate today = LocalDate.now();
        int ok = 0;
        List<String> expired = new ArrayList<>();
        for (String id : ids) {
            HrAttendanceException e = require(id);
            if (!HrAttendanceException.ST_IDENTIFIED.equals(e.getStatus())) {
                throw new ServiceException(422, "仅待处理异常可申诉：" + e.getExcType() + " " + e.getExcDate());
            }
            // 5 工作日期限（逾期默认接受）
            if (workDaysBetween(e.getExcDate(), today) > appealDays) {
                expired.add(id);
                continue;
            }
            e.setStatus(HrAttendanceException.ST_APPEALED);
            e.setAppealReason(reason);
            e.setEvidenceRef(evidence);
            e.setAppealAt(LocalDateTime.now());
            e.setUpdateBy(currentUser());
            e.setUpdateDate(LocalDateTime.now());
            excDao.updateById(e);
            ok++;
        }
        opLog("APPEAL", ids, reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appealed", ok);
        out.put("expiredIds", expired); // 逾期拦截（默认接受不入状态机）
        return out;
    }

    // ================================================================
    // 佐证无效退回
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> returnAppeal(Map<String, Object> payload) {
        String id = str(payload.get("id"));
        String opinion = str(payload.get("opinion"));
        require(id, "异常 ID");
        require(opinion, "退回原因");
        HrAttendanceException e = require(id);
        if (!HrAttendanceException.ST_APPEALED.equals(e.getStatus())) {
            throw new ServiceException(422, "仅申诉中异常可退回");
        }
        e.setStatus(HrAttendanceException.ST_IDENTIFIED);
        e.setConfirmOpinion("佐证无效退回：" + opinion);
        e.setUpdateBy(currentUser());
        e.setUpdateDate(LocalDateTime.now());
        excDao.updateById(e);
        opLog("RETURN", List.of(id), opinion);
        return Map.of("id", id, "status", e.getStatus());
    }

    // ================================================================
    // 批量主管确认
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchMgrConfirm(Map<String, Object> payload) {
        List<String> approveIds = strList(payload.get("approveIds"));
        List<Map<String, Object>> rejectIds = mapList(payload.get("rejectIds"));
        String opinion = str(payload.get("opinion"));
        requireMgrConfirmRight(approveIds, rejectIds);
        int approved = 0;
        int rejected = 0;
        List<String> all = new ArrayList<>(approveIds);
        for (String id : approveIds) {
            HrAttendanceException e = require(id);
            requireStatus(e, HrAttendanceException.ST_APPEALED, "主管确认");
            e.setStatus(HrAttendanceException.ST_MGR_CONFIRMED);
            e.setConfirmOpinion(opinion == null ? "确认通过" : opinion);
            e.setConfirmBy(currentUser());
            e.setConfirmAt(LocalDateTime.now());
            e.setUpdateBy(currentUser());
            e.setUpdateDate(LocalDateTime.now());
            excDao.updateById(e);
            approved++;
        }
        for (Map<String, Object> rej : rejectIds) {
            String id = str(rej.get("id"));
            String op = str(rej.get("opinion"));
            all.add(id);
            HrAttendanceException e = require(id);
            requireStatus(e, HrAttendanceException.ST_APPEALED, "主管驳回");
            e.setStatus(HrAttendanceException.ST_IDENTIFIED); // 驳回回退
            e.setConfirmOpinion("驳回：" + (op == null ? "" : op));
            e.setConfirmBy(currentUser());
            e.setConfirmAt(LocalDateTime.now());
            e.setUpdateBy(currentUser());
            e.setUpdateDate(LocalDateTime.now());
            excDao.updateById(e);
            rejected++;
        }
        opLog("MGR_CONFIRM", all, opinion);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approved", approved);
        out.put("rejected", rejected);
        return out;
    }

    // ================================================================
    // HR 终审 + 月度锁
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> hrFinalize(Map<String, Object> payload) {
        String period = str(payload.get("period"));
        require(period, "期间（yyyyMM）");
        LocalDate start = periodStart(period);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<HrAttendanceException> pendings = excDao.selectList(new LambdaQueryWrapper<HrAttendanceException>()
                .eq(HrAttendanceException::getDelFlag, "0")
                .ge(HrAttendanceException::getExcDate, start)
                .le(HrAttendanceException::getExcDate, end)
                .in(HrAttendanceException::getStatus,
                        HrAttendanceException.ST_IDENTIFIED, HrAttendanceException.ST_APPEALED));
        if (!pendings.isEmpty()) {
            throw new ServiceException(422, "存在 " + pendings.size() + " 条未处理完异常（C-4.7-01），不可终审");
        }
        // 锁定期内全部 HR_FINAL 异常
        List<HrAttendanceException> finals = excDao.selectList(new LambdaQueryWrapper<HrAttendanceException>()
                .eq(HrAttendanceException::getDelFlag, "0")
                .ge(HrAttendanceException::getExcDate, start)
                .le(HrAttendanceException::getExcDate, end)
                .eq(HrAttendanceException::getStatus, HrAttendanceException.ST_MGR_CONFIRMED));
        List<String> ids = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (HrAttendanceException e : finals) {
            e.setStatus(HrAttendanceException.ST_HR_FINAL);
            e.setFinalBy(currentUser());
            e.setFinalAt(now);
            e.setUpdateBy(currentUser());
            e.setUpdateDate(now);
            excDao.updateById(e);
            ids.add(e.getId());
        }
        // 月度锁（涉及员工批量置锁）
        int locked = 0;
        List<String> empIds = finals.stream().map(HrAttendanceException::getEmpId).distinct().toList();
        for (String empId : empIds) {
            HrAttendanceMonthLock existing = one(lockDao.selectList(new LambdaQueryWrapper<HrAttendanceMonthLock>()
                    .eq(HrAttendanceMonthLock::getPeriod, period)
                    .eq(HrAttendanceMonthLock::getEmpId, empId)));
            if (existing != null) {
                existing.setLockedAt(now);
                existing.setLockedBy(currentUser());
                existing.setCorrectAt(null);
                existing.setCorrectBy(null);
                existing.setUpdateBy(currentUser());
                existing.setUpdateDate(now);
                lockDao.updateById(existing);
            } else {
                HrAttendanceMonthLock lock = new HrAttendanceMonthLock();
                lock.setId(uuid());
                lock.setPeriod(period);
                lock.setEmpId(empId);
                lock.setLockedAt(now);
                lock.setLockedBy(currentUser());
                lock.setCreateBy(currentUser());
                lock.setCreateDate(now);
                lockDao.insert(lock);
            }
            locked++;
        }
        opLog("HR_FINAL", ids, "终审锁定 " + period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("finalized", ids.size());
        out.put("lockedEmployees", locked);
        return out;
    }

    // ================================================================
    // 更正（解锁单员工行）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> correct(Map<String, Object> payload) {
        String id = str(payload.get("exceptionId"));
        String reason = str(payload.get("reason"));
        require(id, "异常 ID");
        require(reason, "更正原因");
        HrAttendanceException e = require(id);
        if (!HrAttendanceException.ST_HR_FINAL.equals(e.getStatus())) {
            throw new ServiceException(422, "仅终审后异常可更正");
        }
        e.setStatus(HrAttendanceException.ST_CORRECTED);
        e.setConfirmOpinion("更正：" + reason);
        e.setUpdateBy(currentUser());
        e.setUpdateDate(LocalDateTime.now());
        excDao.updateById(e);
        // 解锁该员工该月（汇总重聚合后可重新终审）
        LocalDate d = e.getExcDate();
        String period = String.format("%04d%02d", d.getYear(), d.getMonthValue());
        HrAttendanceMonthLock lock = one(lockDao.selectList(new LambdaQueryWrapper<HrAttendanceMonthLock>()
                .eq(HrAttendanceMonthLock::getPeriod, period)
                .eq(HrAttendanceMonthLock::getEmpId, e.getEmpId())));
        if (lock != null) {
            lock.setCorrectAt(LocalDateTime.now());
            lock.setCorrectBy(currentUser());
            lock.setRemark(reason);
            lock.setUpdateBy(currentUser());
            lock.setUpdateDate(LocalDateTime.now());
            lockDao.updateById(lock);
        }
        opLog("CORRECT", List.of(id), reason);
        return Map.of("id", id, "status", e.getStatus(), "period", period);
    }

    // ================================================================
    // 催办 + 我的异常
    // ================================================================

    @Override
    public List<Map<String, Object>> pendingConfirmations() {
        int deadline = intParam("MGR_CONFIRM_DEADLINE_DAYS", 3);
        LocalDate cutoff = LocalDate.now().plusDays(deadline);
        List<HrAttendanceException> pendings = excDao.selectList(new LambdaQueryWrapper<HrAttendanceException>()
                .eq(HrAttendanceException::getDelFlag, "0")
                .eq(HrAttendanceException::getStatus, HrAttendanceException.ST_APPEALED)
                .le(HrAttendanceException::getExcDate, cutoff));
        Map<String, Map<String, Object>> byDept = new LinkedHashMap<>();
        for (HrAttendanceException e : pendings) {
            String deptId = e.getDeptId() == null ? "?" : e.getDeptId();
            Map<String, Object> row = byDept.computeIfAbsent(deptId, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                HrDept dept = deptDao.selectById(k);
                m.put("deptId", k);
                m.put("deptName", dept == null ? k : dept.getName());
                m.put("count", 0);
                return m;
            });
            row.put("count", ((Integer) row.get("count")) + 1);
        }
        return new ArrayList<>(byDept.values());
    }

    @Override
    public List<HrAttendanceException> myExceptions(String empId, String period) {
        LocalDate start = periodStart(period);
        return excDao.selectList(new LambdaQueryWrapper<HrAttendanceException>()
                .eq(HrAttendanceException::getEmpId, empId)
                .ge(HrAttendanceException::getExcDate, start)
                .le(HrAttendanceException::getExcDate, start.withDayOfMonth(start.lengthOfMonth()))
                .eq(HrAttendanceException::getDelFlag, "0")
                .orderByAsc(HrAttendanceException::getExcDate));
    }

    // ================================================================
    // 3.4 月度汇总（D6 查询聚合 + 锁定 + C-4.7-01 挂起标记）
    // ================================================================

    @Override
    public List<Map<String, Object>> monthlySummary(String period) {
        LocalDate start = periodStart(period);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<HrEmployee> emps = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .in(HrEmployee::getEmpStatus, "PROBATION", "REGULAR")
                .orderByAsc(HrEmployee::getEmpNo));
        // 期内异常全量（含非终态——挂起判定用）
        List<HrAttendanceException> excs = excDao.selectList(new LambdaQueryWrapper<HrAttendanceException>()
                .eq(HrAttendanceException::getDelFlag, "0")
                .ge(HrAttendanceException::getExcDate, start)
                .le(HrAttendanceException::getExcDate, end));
        List<Map<String, Object>> leaves = jdbcLeaveSummary(start, end);
        List<Map<String, Object>> ots = jdbcOvertimeSummary(start, end);
        List<HrAttendanceMonthLock> locks = lockDao.selectList(new LambdaQueryWrapper<HrAttendanceMonthLock>()
                .eq(HrAttendanceMonthLock::getPeriod, period)
                .eq(HrAttendanceMonthLock::getDelFlag, "0"));

        List<Map<String, Object>> out = new ArrayList<>();
        for (HrEmployee e : emps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("empId", e.getId());
            row.put("empNo", e.getEmpNo());
            row.put("name", e.getName());
            // 异常分型计数（仅终态计入；非终态计挂起）
            int late = 0;
            int early = 0;
            int missing = 0;
            int absent = 0;
            int pending = 0;
            for (HrAttendanceException x : excs) {
                if (!e.getId().equals(x.getEmpId())) {
                    continue;
                }
                if (HrAttendanceException.ST_IDENTIFIED.equals(x.getStatus())
                        || HrAttendanceException.ST_APPEALED.equals(x.getStatus())) {
                    pending++;
                    continue; // 未处理完不计入终态计数
                }
                switch (x.getExcType()) {
                    case HrAttendanceException.TYPE_LATE -> late++;
                    case HrAttendanceException.TYPE_EARLY -> early++;
                    case HrAttendanceException.TYPE_MISSING -> missing++;
                    case HrAttendanceException.TYPE_ABSENT -> absent++;
                    default -> { }
                }
            }
            row.put("lateCount", late);
            row.put("earlyCount", early);
            row.put("missingCount", missing);
            row.put("absentDays", absent);
            // 请假分型天数 + 扣款
            java.math.BigDecimal deduct = java.math.BigDecimal.ZERO;
            Map<String, java.math.BigDecimal> leaveDays = new LinkedHashMap<>();
            for (Map<String, Object> lv : leaves) {
                if (!e.getId().equals(lv.get("EMP_ID"))) {
                    continue;
                }
                String code = String.valueOf(lv.get("TYPE_CODE"));
                java.math.BigDecimal d = (java.math.BigDecimal) lv.get("DAYS");
                leaveDays.merge(code, d, java.math.BigDecimal::add);
                java.math.BigDecimal amt = (java.math.BigDecimal) lv.get("DEDUCT");
                if (amt != null) {
                    deduct = deduct.add(amt);
                }
            }
            row.put("leaveDays", leaveDays);
            row.put("leaveDeduct", deduct);
            // 加班分型时长 + 工资小计
            java.math.BigDecimal otHours = java.math.BigDecimal.ZERO;
            java.math.BigDecimal otWage = java.math.BigDecimal.ZERO;
            for (Map<String, Object> ot : ots) {
                if (!e.getId().equals(ot.get("EMP_ID"))) {
                    continue;
                }
                java.math.BigDecimal h = (java.math.BigDecimal) ot.get("HOURS");
                java.math.BigDecimal w = (java.math.BigDecimal) ot.get("WAGE");
                if (h != null) {
                    otHours = otHours.add(h);
                }
                if (w != null) {
                    otWage = otWage.add(w);
                }
            }
            row.put("overtimeHours", otHours);
            row.put("overtimeWage", otWage);
            // 锁定与挂起（C-4.7-01：存在未处理完异常 → PENDING 挂起，薪酬应暂停该员工核算）
            boolean locked = locks.stream().anyMatch(l -> e.getId().equals(l.getEmpId()));
            row.put("locked", locked);
            row.put("pendingCount", pending);
            row.put("pendingHold", pending > 0); // C-4.7-01 挂起标记（10.5 读取后暂停核算）
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> jdbcLeaveSummary(LocalDate start, LocalDate end) {
        try {
            return jdbc.queryForList(
                    "SELECT EMP_ID, TYPE_CODE, SUM(DURATION) AS DAYS, SUM(COALESCE(DEDUCT_AMOUNT,0)) AS DEDUCT "
                            + "FROM hr_leave_apply WHERE STATUS='APPROVED' AND DEL_FLAG='0' "
                            + "AND START_DATE >= ? AND END_DATE <= ? GROUP BY EMP_ID, TYPE_CODE", start, end);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> jdbcOvertimeSummary(LocalDate start, LocalDate end) {
        try {
            return jdbc.queryForList(
                    "SELECT EMP_ID, SUM(VALID_HOURS) AS HOURS, SUM(COALESCE(WAGE_AMOUNT,0)) AS WAGE "
                            + "FROM hr_overtime_confirm WHERE DEL_FLAG='0' AND SPLIT_CHOICE='WAGE' "
                            + "AND OT_DATE >= ? AND OT_DATE <= ? GROUP BY EMP_ID", start, end);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 主管确认权限：操作人须为异常员工部门主管（无主管放行 HR 代确认留痕——D5 同调岗先例）。 */
    private void requireMgrConfirmRight(List<String> approveIds, List<Map<String, Object>> rejectIds) {
        String user = currentUser();
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        boolean hrOrMgr = auth != null && auth.getAuthorities() != null && auth.getAuthorities().stream().anyMatch(
                a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_HR".equals(a.getAuthority())
                        || "ROLE_HR_MGR".equals(a.getAuthority()));
        List<String> all = new ArrayList<>(approveIds);
        rejectIds.forEach(r -> all.add(String.valueOf(r.get("id"))));
        for (String id : all) {
            HrAttendanceException e = require(id);
            HrEmployee emp = empDao.selectById(e.getEmpId());
            HrDept dept = emp == null || emp.getDeptId() == null ? null : deptDao.selectById(emp.getDeptId());
            if (dept == null || dept.getManagerEmpId() == null || dept.getManagerEmpId().isBlank()) {
                log.info("主管确认：部门无主管，HR 代确认（D5 放行标注）");
                return;
            }
            HrEmployee mgr = empDao.selectById(dept.getManagerEmpId());
            if (mgr != null && user != null && user.equals(mgr.getEmpNo())) {
                return; // 主管本人
            }
            if (hrOrMgr) {
                log.info("主管确认：非主管本人，HR/管理员代确认（留痕）");
                return;
            }
            throw new ServiceException(422, "确认须由部门主管本人操作（主管：" + mgr.getName() + "）");
        }
    }

    private void opLog(String opType, List<String> ids, String reason) {
        HrAttendanceOpLog l = new HrAttendanceOpLog();
        l.setId(uuid());
        l.setOpType(opType);
        l.setOperator(currentUser());
        l.setOpAt(LocalDateTime.now());
        try {
            l.setApplicableIdsJson(objectMapper.writeValueAsString(ids));
        } catch (Exception e) {
            l.setApplicableIdsJson("[]");
        }
        l.setReason(reason);
        opLogDao.insert(l);
    }

    /** 工作日差（不含起止周末）。 */
    private long workDaysBetween(LocalDate from, LocalDate to) {
        long days = 0;
        LocalDate d = from.plusDays(1);
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
            d = d.plusDays(1);
        }
        return days;
    }

    private void requireStatus(HrAttendanceException e, String expect, String action) {
        if (!expect.equals(e.getStatus())) {
            throw new ServiceException(422, action + "状态非法（当前：" + e.getStatus() + "）");
        }
    }

    private HrAttendanceException require(String id) {
        HrAttendanceException e = excDao.selectById(id);
        if (e == null) {
            throw new ServiceException(404, "异常记录不存在：" + id);
        }
        return e;
    }

    private static LocalDate periodStart(String period) {
        try {
            return LocalDate.of(Integer.parseInt(period.substring(0, 4)),
                    Integer.parseInt(period.substring(4, 6)), 1);
        } catch (Exception e) {
            throw new ServiceException(422, "期间格式非法（yyyyMM）：" + period);
        }
    }

    private int intParam(String key, int def) {
        String v = sysParamService.getValue(key);
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object o) {
        if (o instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return List.of();
    }

    private static HrAttendanceMonthLock one(List<HrAttendanceMonthLock> list) {
        return list.isEmpty() ? null : list.get(0);
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
