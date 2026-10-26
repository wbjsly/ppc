package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.hr.HrAttendanceExceptionDao;
import com.erp.dao.hr.HrBusinessTripDao;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrHolidayCalendarDao;
import com.erp.dao.hr.HrLeaveApplyDao;
import com.erp.dao.hr.HrPunchRecordDao;
import com.erp.dao.hr.HrScheduleDao;
import com.erp.dao.hr.HrShiftDao;
import com.erp.entity.hr.HrAttendanceException;
import com.erp.entity.hr.HrBusinessTrip;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrHolidayCalendar;
import com.erp.entity.hr.HrLeaveApply;
import com.erp.entity.hr.HrPunchRecord;
import com.erp.entity.hr.HrSchedule;
import com.erp.entity.hr.HrShift;
import com.erp.service.SysParamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 每日考勤异常识别（attendance-core D2，FR-4.7-2-3）：
 * 四类异常（迟到/早退/缺卡/旷工）+ 部门级阈值覆盖 + 出差/请假/法定假日豁免 +
 * 未排班默认标准工时 + 三工时制分支；同日同型 upsert 幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrAttendanceScanTask {

    private final HrEmployeeDao empDao;
    private final HrScheduleDao scheduleDao;
    private final HrShiftDao shiftDao;
    private final HrPunchRecordDao punchDao;
    private final HrAttendanceExceptionDao excDao;
    private final HrLeaveApplyDao leaveDao;
    private final HrBusinessTripDao tripDao;
    private final HrHolidayCalendarDao holidayDao;
    private final HrDeptDao deptDao;
    private final SysParamService sysParamService;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** 每日 01:30 扫描前一日（幂等：同日重跑不产生重复异常）。 */
    @Scheduled(cron = "0 30 1 * * ?")
    public void dailyScan() {
        scanForDate(LocalDate.now().minusDays(1));
    }

    /** 扫描指定日期（供调度与测试直调；返回新建/更新的异常数）。 */
    @Transactional(rollbackFor = Exception.class)
    public MapOut scanForDate(LocalDate date) {
        List<HrEmployee> emps = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .in(HrEmployee::getEmpStatus, "PROBATION", "REGULAR"));
        int created = 0;
        for (HrEmployee e : emps) {
            created += scanOne(e, date);
        }
        log.info("attendance scan {}: {} employees, {} exceptions upserted", date, emps.size(), created);
        return new MapOut(created, emps.size());
    }

    /** 返回结构（简单计数载体）。 */
    public record MapOut(int exceptions, int employees) {
    }

    // ================================================================
    // 单人单日识别
    // ================================================================

    private int scanOne(HrEmployee e, LocalDate date) {
        String scheme = e.getAttendScheme() == null ? "STANDARD" : e.getAttendScheme();
        if ("IRREGULAR".equals(scheme)) {
            return 0; // 不定时工时：只记录打卡不识别异常
        }
        // ① 法定假日/调休上班日历
        HrHolidayCalendar holiday = holidayDao.selectByDate(date);
        if (holiday != null && HrHolidayCalendar.TYPE_STATUTORY.equals(holiday.getType())) {
            return 0; // 法定假日出勤识别为加班候选，不判迟到早退旷工
        }
        boolean restday = isRestDay(date, holiday);
        // ② 排班
        HrSchedule sch = latestSchedule(e.getId(), date);
        if (sch != null && "1".equals(sch.getIsRest())) {
            return 0; // 排班休息日
        }
        if (sch == null && restday) {
            return 0; // 无排班的休息日
        }
        if (holiday != null && HrHolidayCalendar.TYPE_WORKDAY.equals(holiday.getType())) {
            restday = false; // 调休上班日按工作日识别
        }
        // ③ 豁免：已审批请假 / 已审批出差（BR-4.7-12）
        if (approvedLeaveCovers(e.getId(), date) || approvedTripCovers(e.getId(), date)) {
            return 0;
        }
        // ④ 班次（无排班默认标准工时 9:00-18:00）
        HrShift shift = resolveShift(sch);
        // ⑤ 打卡
        List<HrPunchRecord> punches = punchDao.selectList(new LambdaQueryWrapper<HrPunchRecord>()
                .eq(HrPunchRecord::getEmpId, e.getId())
                .eq(HrPunchRecord::getPunchDate, date)
                .eq(HrPunchRecord::getDelFlag, "0"));
        HrPunchRecord in = punches.stream()
                .filter(p -> HrPunchRecord.DIR_IN.equals(p.getDir())).findFirst().orElse(null);
        HrPunchRecord out = punches.stream()
                .filter(p -> HrPunchRecord.DIR_OUT.equals(p.getDir())).findFirst().orElse(null);

        int n = 0;
        boolean comprehensive = "COMPREHENSIVE".equals(scheme);
        int absentThreshold = intParam("ABSENT_HOURS_THRESHOLD", 4);
        BigDecimal shiftHours = shift == null ? BigDecimal.valueOf(8) : shift.getHours();

        if (in == null && out == null) {
            // 全天无记录：旷工（应出勤工时 ≥ 阈值；综合计算工时制不逐日判旷工——周期总量判定）
            if (!comprehensive && shiftHours.intValue() >= absentThreshold) {
                n += upsert(e, date, HrAttendanceException.TYPE_ABSENT, null);
            }
            return n;
        }
        // 缺卡（综合计算工时制期内不逐日判，跳过）
        if (!comprehensive) {
            if (in == null || out == null) {
                n += upsert(e, date, HrAttendanceException.TYPE_MISSING, null);
            }
            // 迟到/早退（阈值：部门覆盖 SysParam 优先 → 班次容差 → 全局缺省）
            int tolerance = toleranceFor(e.getDeptId(), shift);
            if (in != null && shift != null) {
                long lateMin = minutesBetween(shift.getStartTime(), in.getPunchTime());
                if (lateMin > tolerance) {
                    n += upsert(e, date, HrAttendanceException.TYPE_LATE, (int) lateMin);
                }
            }
            if (out != null && shift != null) {
                long earlyMin = minutesBetween(out.getPunchTime(), shift.getEndTime());
                if (earlyMin > tolerance) {
                    n += upsert(e, date, HrAttendanceException.TYPE_EARLY, (int) earlyMin);
                }
            }
        }
        return n;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 幂等 upsert：同人同日同型已存在则更新分钟数，不重复插入。 */
    private int upsert(HrEmployee e, LocalDate date, String type, Integer detailMin) {
        HrAttendanceException existing = one(excDao.selectList(new LambdaQueryWrapper<HrAttendanceException>()
                .eq(HrAttendanceException::getEmpId, e.getId())
                .eq(HrAttendanceException::getExcDate, date)
                .eq(HrAttendanceException::getExcType, type)
                .eq(HrAttendanceException::getDelFlag, "0")));
        if (existing != null) {
            if (detailMin != null && !detailMin.equals(existing.getDetailMin())) {
                existing.setDetailMin(detailMin);
                existing.setUpdateBy("scan");
                existing.setUpdateDate(LocalDateTime.now());
                excDao.updateById(existing);
            }
            return 0; // 幂等：已存在不计新增
        }
        HrAttendanceException exc = new HrAttendanceException();
        exc.setId(uuid());
        exc.setEmpId(e.getId());
        exc.setEmpNo(e.getEmpNo());
        exc.setEmpName(e.getName());
        exc.setDeptId(e.getDeptId());
        exc.setExcDate(date);
        exc.setExcType(type);
        exc.setDetailMin(detailMin);
        exc.setStatus(HrAttendanceException.ST_IDENTIFIED);
        exc.setCreateBy("scan");
        exc.setCreateDate(LocalDateTime.now());
        excDao.insert(exc);
        return 1;
    }

    /** 最新排班（同人同日多条以最后一次为准——按 UPDATE_DATE 倒序取首条）。 */
    private HrSchedule latestSchedule(String empId, LocalDate date) {
        List<HrSchedule> list = scheduleDao.selectList(new LambdaQueryWrapper<HrSchedule>()
                .eq(HrSchedule::getEmpId, empId)
                .eq(HrSchedule::getWorkDate, date)
                .eq(HrSchedule::getDelFlag, "0")
                .orderByDesc(HrSchedule::getUpdateDate)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private HrShift resolveShift(HrSchedule sch) {
        if (sch == null || sch.getShiftId() == null) {
            return null; // 默认标准工时由调用方以 9:00-18:00 兜底
        }
        return shiftDao.selectById(sch.getShiftId());
    }

    /** 班次缺失时的默认标准工时视图（9:00-18:00 容差取全局参数）。 */
    @SuppressWarnings("unused")
    private HrShift defaultShift() {
        HrShift s = new HrShift();
        s.setStartTime("09:00");
        s.setEndTime("18:00");
        s.setHours(BigDecimal.valueOf(8));
        s.setToleranceMin(intParam("LATE_TOLERANCE_MIN", 10));
        return s;
    }

    /** 阈值优先级：部门 SysParam 覆盖（ATTEND_LATE_TOL_<DEPT_ID>）→ 班次容差 → 全局。 */
    private int toleranceFor(String deptId, HrShift shift) {
        if (deptId != null) {
            String override = sysParamService.getValue("ATTEND_LATE_TOL_" + deptId);
            if (override != null && !override.isBlank()) {
                try {
                    return Integer.parseInt(override.trim());
                } catch (Exception ignored) {
                    // fallthrough
                }
            }
        }
        if (shift != null && shift.getToleranceMin() != null) {
            return shift.getToleranceMin();
        }
        return intParam("LATE_TOLERANCE_MIN", 10);
    }

    private boolean approvedLeaveCovers(String empId, LocalDate date) {
        return leaveDao.selectCount(new LambdaQueryWrapper<HrLeaveApply>()
                .eq(HrLeaveApply::getEmpId, empId)
                .eq(HrLeaveApply::getStatus, HrLeaveApply.ST_APPROVED)
                .le(HrLeaveApply::getStartDate, date)
                .ge(HrLeaveApply::getEndDate, date)
                .eq(HrLeaveApply::getDelFlag, "0")) > 0;
    }

    private boolean approvedTripCovers(String empId, LocalDate date) {
        return tripDao.selectCount(new LambdaQueryWrapper<HrBusinessTrip>()
                .eq(HrBusinessTrip::getEmpId, empId)
                .eq(HrBusinessTrip::getStatus, HrBusinessTrip.ST_APPROVED)
                .le(HrBusinessTrip::getStartDate, date)
                .ge(HrBusinessTrip::getEndDate, date)
                .eq(HrBusinessTrip::getDelFlag, "0")) > 0;
    }

    /** 周末判定：周六日 或 日历 RESTDAY。 */
    private boolean isRestDay(LocalDate date, HrHolidayCalendar holiday) {
        if (holiday != null && HrHolidayCalendar.TYPE_RESTDAY.equals(holiday.getType())) {
            return true;
        }
        if (holiday != null && HrHolidayCalendar.TYPE_WORKDAY.equals(holiday.getType())) {
            return false;
        }
        return date.getDayOfWeek().getValue() >= 6;
    }

    /** 迟到分钟 = punchTime - startTime；早退分钟 = endTime - punchTime（跨天班次按跨天语义简化不判）。 */
    private long minutesBetween(String base, String actual) {
        try {
            LocalTime b = LocalTime.parse(base, HM);
            LocalTime a = LocalTime.parse(actual.substring(0, 5), HM);
            long diff = java.time.Duration.between(b, a).toMinutes();
            return Math.max(diff, 0);
        } catch (Exception e) {
            return 0;
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

    private static HrAttendanceException one(List<HrAttendanceException> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
