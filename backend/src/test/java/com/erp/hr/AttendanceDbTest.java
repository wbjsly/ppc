package com.erp.hr;

import com.erp.dao.hr.HrAttendanceExceptionDao;
import com.erp.dao.hr.HrHolidayCalendarDao;
import com.erp.dao.hr.HrLeaveTypeDao;
import com.erp.dao.hr.HrPunchRecordDao;
import com.erp.dao.hr.HrScheduleDao;
import com.erp.dao.hr.HrShiftDao;
import com.erp.entity.hr.HrAttendanceException;
import com.erp.entity.hr.HrHolidayCalendar;
import com.erp.entity.hr.HrLeaveType;
import com.erp.entity.hr.HrPunchRecord;
import com.erp.entity.hr.HrSchedule;
import com.erp.entity.hr.HrShift;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10-3 考勤 DbTest 骨架（attendance-core：班次/排班/打卡/异常/假日/假期类型落库）。 */
@SpringBootTest
class AttendanceDbTest {

    @Autowired
    private HrShiftDao shiftDao;
    @Autowired
    private HrScheduleDao scheduleDao;
    @Autowired
    private HrPunchRecordDao punchDao;
    @Autowired
    private HrAttendanceExceptionDao excDao;
    @Autowired
    private HrHolidayCalendarDao holidayDao;
    @Autowired
    private HrLeaveTypeDao leaveTypeDao;
    @Autowired
    private com.erp.dao.hr.HrEmployeeDao empDao;
    @Autowired
    private com.erp.service.hr.HrShiftService shiftService;
    @Autowired
    private com.erp.service.hr.HrPunchService punchService;
    @Autowired
    private com.erp.schedule.HrAttendanceScanTask scanTask;
    @Autowired
    private com.erp.service.hr.HrAttendanceService attendanceService;
    @Autowired
    private com.erp.service.hr.HrMakeupPunchService makeupService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_shift WHERE SHIFT_NO LIKE 'SHIFT%'");
        jdbc.update("DELETE FROM hr_schedule WHERE ID LIKE 'junit-sch-%' OR EMP_ID LIKE 'junit-emp-%'");
        jdbc.update("DELETE FROM hr_punch_record WHERE ID LIKE 'junit-punch-%' OR EMP_ID LIKE 'junit-emp-%'");
        jdbc.update("DELETE FROM hr_attendance_exception WHERE EMP_ID LIKE 'junit-emp-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'junit-emp-%' OR EMP_NO LIKE 'JATT-%'");
        jdbc.update("DELETE FROM hr_business_trip WHERE ID LIKE 'junit-trip-%' OR EMP_ID LIKE 'junit-emp-%'");
    }

    @Test
    void entitiesPersist() {
        // 班次
        HrShift shift = new HrShift();
        shift.setId(uuid());
        shift.setShiftNo("SHIFT0001");
        shift.setName("常白班");
        shift.setStartTime("09:00");
        shift.setEndTime("18:00");
        shift.setHours(new BigDecimal("8"));
        shift.setCrossDay("0");
        shift.setOvertimeEligible("1");
        shift.setToleranceMin(10);
        shift.setStatus("1");
        shift.setCreateBy("junit");
        shiftDao.insert(shift);
        assertNotNull(shiftDao.selectById(shift.getId()));

        // 排班
        HrSchedule sch = new HrSchedule();
        sch.setId(uuid());
        sch.setEmpId("junit-emp-1");
        sch.setWorkDate(LocalDate.of(2099, 10, 8));
        sch.setShiftId(shift.getId());
        sch.setIsRest("0");
        sch.setSource("SINGLE");
        sch.setCreateBy("junit");
        scheduleDao.insert(sch);
        assertNotNull(scheduleDao.selectById(sch.getId()));

        // 打卡
        HrPunchRecord punch = new HrPunchRecord();
        punch.setId(uuid());
        punch.setEmpId("junit-emp-1");
        punch.setPunchDate(LocalDate.of(2099, 10, 8));
        punch.setPunchTime("09:05:00");
        punch.setDir("IN");
        punch.setSource("WEB");
        punch.setSuspicious("0");
        punch.setCreateBy("junit");
        punchDao.insert(punch);
        assertNotNull(punchDao.selectById(punch.getId()));

        // 异常
        HrAttendanceException exc = new HrAttendanceException();
        exc.setId(uuid());
        exc.setEmpId("junit-emp-1");
        exc.setExcDate(LocalDate.of(2099, 10, 8));
        exc.setExcType("LATE");
        exc.setDetailMin(5);
        exc.setStatus(HrAttendanceException.ST_IDENTIFIED);
        exc.setCreateBy("junit");
        excDao.insert(exc);
        HrAttendanceException loaded = excDao.selectById(exc.getId());
        assertNotNull(loaded);
        assertEquals(HrAttendanceException.ST_IDENTIFIED, loaded.getStatus());

        // 假日/假期类型（种子校验）
        HrHolidayCalendar hd = holidayDao.selectByDate(LocalDate.of(2026, 10, 1));
        assertNotNull(hd, "国庆预置应存在");
        assertEquals(HrHolidayCalendar.TYPE_STATUTORY, hd.getType());
        assertEquals(8, jdbc.queryForObject("SELECT COUNT(*) FROM hr_leave_type WHERE DEL_FLAG='0'", Integer.class),
                "假期类型 8 类种子");
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ================================================================
    // 2.3 组 2 场景：班次/排班/打卡
    // ================================================================

    private void seedEmp(String suffix, String deptId) {
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, ?, 'REGULAR', 'junit', '0', 0)",
                "junit-emp-" + suffix, "JATT-" + suffix.toUpperCase(), "考勤员工" + suffix, "JATTID-" + suffix, deptId);
    }

    @Test
    void nightShiftCrossDayHours() {
        // 跨夜班 22:00-06:00 → 工时 8、crossDay=1
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("name", "夜班");
        p.put("startTime", "22:00");
        p.put("endTime", "06:00");
        HrShift night = shiftService.create(p);
        assertEquals("1", night.getCrossDay(), "跨天标记");
        assertEquals(0, new BigDecimal("8.00").compareTo(night.getHours()), "跨夜工时 8 小时");
        // 正常班 09:00-18:00 → 9 小时
        Map<String, Object> p2 = new java.util.LinkedHashMap<>();
        p2.put("name", "常白班");
        p2.put("startTime", "09:00");
        p2.put("endTime", "18:00");
        HrShift day = shiftService.create(p2);
        assertEquals("0", day.getCrossDay());
        assertEquals(0, new BigDecimal("9.00").compareTo(day.getHours()));
    }

    @Test
    void bulkScheduleWithException() {
        seedEmp("b1", "junit-dept-b");
        seedEmp("b2", "junit-dept-b");
        Map<String, Object> sp = new java.util.LinkedHashMap<>();
        sp.put("name", "批量班");
        sp.put("startTime", "08:30");
        sp.put("endTime", "17:30");
        HrShift shift = shiftService.create(sp);
        // 部门批量 2099-03-01~03-03
        Map<String, Object> bulk = new java.util.LinkedHashMap<>();
        bulk.put("deptId", "junit-dept-b");
        bulk.put("fromDate", "2099-03-01");
        bulk.put("toDate", "2099-03-03");
        bulk.put("shiftId", shift.getId());
        Map<String, Object> out = shiftService.bulkSchedule(bulk);
        assertEquals(2, ((Number) out.get("empCount")).intValue());
        assertEquals(6, ((Number) out.get("rows")).intValue(), "2 人 × 3 天");
        // 个别例外：b1 3-02 改夜班（同日重排以最后一次为准）
        Map<String, Object> sp2 = new java.util.LinkedHashMap<>();
        sp2.put("name", "例外夜班");
        sp2.put("startTime", "22:00");
        sp2.put("endTime", "06:00");
        HrShift night = shiftService.create(sp2);
        Map<String, Object> ex = new java.util.LinkedHashMap<>();
        ex.put("empId", "junit-emp-b1");
        ex.put("workDate", "2099-03-02");
        ex.put("shiftId", night.getId());
        shiftService.schedule(ex);
        int nightCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_schedule WHERE EMP_ID='junit-emp-b1' AND WORK_DATE='2099-03-02' "
                        + "AND SHIFT_ID=?", Integer.class, night.getId());
        assertEquals(1, nightCnt, "同人同日只保留最后一次排班");
    }

    @Test
    void copyMonthConflictL4() {
        seedEmp("c1", "junit-dept-c");
        Map<String, Object> sp = new java.util.LinkedHashMap<>();
        sp.put("name", "复制班");
        sp.put("startTime", "09:00");
        sp.put("endTime", "18:00");
        HrShift shift = shiftService.create(sp);
        // 2099-04-01 排一条
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("empId", "junit-emp-c1");
        s.put("workDate", "2099-04-01");
        s.put("shiftId", shift.getId());
        shiftService.schedule(s);
        // 复制 209904 → 209905（5-01 无冲突；但 4 月只排了 1 天，复制后 5-01 有 1 条）
        Map<String, Object> copy = shiftService.copyMonth("209904", "209905");
        assertEquals(1, ((Number) copy.get("copied")).intValue());
        assertEquals(0, ((Number) copy.get("conflicts")).intValue());
        // 再复制一次 → 1 条冲突（同日已有排班被覆盖，L4 留痕）
        Map<String, Object> copy2 = shiftService.copyMonth("209904", "209905");
        assertEquals(1, ((Number) copy2.get("conflicts")).intValue(), "重复复制冲突计数");
    }

    @Test
    void duplicatePunchBlocked() {
        seedEmp("p1", "junit-dept-p");
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("empId", "junit-emp-p1");
        p.put("dir", "IN");
        punchService.webPunch(p);
        // 同向重复 → 422
        com.erp.common.ServiceException e = org.junit.jupiter.api.Assertions.assertThrows(
                com.erp.common.ServiceException.class, () -> punchService.webPunch(p));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("已有上班打卡"), e.getMessage());
        // 反向合法
        Map<String, Object> p2 = new java.util.LinkedHashMap<>();
        p2.put("empId", "junit-emp-p1");
        p2.put("dir", "OUT");
        assertNotNull(punchService.webPunch(p2));
    }

    @Test
    void csvImportDedupAndErrorList() {
        seedEmp("csv1", "junit-dept-csv");
        // 预置一条 09:00 IN（重复行将被跳过）
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('junit-punch-pre','junit-emp-csv1', "
                + "'2099-05-08','09:00:00','IN','WEB','0','junit','0',0)");
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        // 行1：与预置重复 → 跳过
        rows.add(Map.of("empNo", "JATT-CSV1", "punchDate", "2099-05-08", "punchTime", "09:00", "dir", "IN"));
        // 行2：工号不存在 → 报错清单
        rows.add(Map.of("empNo", "NO-SUCH", "punchDate", "2099-05-08", "punchTime", "18:00", "dir", "OUT"));
        // 行3：正常
        rows.add(Map.of("empNo", "JATT-CSV1", "punchDate", "2099-05-08", "punchTime", "18:05", "dir", "OUT"));
        Map<String, Object> out = punchService.importCsv(rows);
        assertEquals(1, ((Number) out.get("success")).intValue());
        assertEquals(1, ((Number) out.get("skippedDuplicate")).intValue());
        assertEquals(1, ((List<?>) out.get("errors")).size(), "报错清单 1 行");
        assertNotNull(out.get("batchId"), "导入批次号");
    }

    @Test
    void gpsPunchSuspiciousFlag() {
        seedEmp("g1", "junit-dept-g");
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("empId", "junit-emp-g1");
        p.put("dir", "IN");
        p.put("lat", "31.230400");
        p.put("lng", "121.473700");
        HrPunchRecord rec = punchService.gpsPunch(p);
        assertEquals("GPS", rec.getSource());
        assertNotNull(rec.getLat(), "经纬度落库");
        assertEquals("0", rec.getSuspicious());
    }

    // ================================================================
    // 3.5 组 3 场景：异常识别/申诉/确认/终审/补卡/汇总
    // ================================================================

    /** 造一名员工 + 一条 9:00-18:00 排班（指定日期）。 */
    private void seedScheduledEmp(String suffix, LocalDate date) {
        seedEmp(suffix, "junit-dept-" + suffix);
        Map<String, Object> sp = new java.util.LinkedHashMap<>();
        sp.put("name", "识别班-" + suffix);
        sp.put("startTime", "09:00");
        sp.put("endTime", "18:00");
        sp.put("toleranceMin", 10);
        HrShift shift = shiftService.create(sp);
        Map<String, Object> s = new java.util.LinkedHashMap<>();
        s.put("empId", "junit-emp-" + suffix);
        s.put("workDate", date.toString());
        s.put("shiftId", shift.getId());
        shiftService.schedule(s);
    }

    @Test
    void scanIdentifiesFourTypesAndIdempotent() {
        LocalDate d = LocalDate.of(2099, 6, 3); // 周三
        // a: 迟到（9:20 打卡 > 9:00+10min 容差）
        // 固定 09:20 显式落库：webPunch 取 LocalTime.now()，凌晨跑测试会时点漂移（00:xx 打卡不判迟到）
        seedScheduledEmp("sa", d);
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('junit-punch-sa','junit-emp-sa',?,"
                + "'09:20:00','IN','WEB','0','junit','0',0)", d);
        // b: 缺卡（只有上班无下班）
        seedScheduledEmp("sb", d);
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('junit-punch-sb','junit-emp-sb',?,"
                + "'09:00:00','IN','WEB','0','junit','0',0)", d);
        // c: 旷工（全天无打卡）
        seedScheduledEmp("sc", d);
        // d: 出差豁免（全天无打卡但有已批出差单 BR-4.7-12）
        seedScheduledEmp("sd", d);
        jdbc.update("INSERT INTO hr_business_trip (ID, EMP_ID, START_DATE, END_DATE, STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('junit-trip-d','junit-emp-sd',?,?, 'APPROVED', "
                + "'junit','0',0)", d, d);

        scanTask.scanForDate(d);
        assertEquals(1, countExc("junit-emp-sa", d, "LATE"), "迟到识别");
        assertEquals(1, countExc("junit-emp-sb", d, "MISSING"), "缺卡识别");
        assertEquals(1, countExc("junit-emp-sc", d, "ABSENT"), "旷工识别");
        assertEquals(0, countExc("junit-emp-sd", d, "ABSENT"), "出差豁免不识别（BR-4.7-12）");

        // 幂等重跑：不新增
        scanTask.scanForDate(d);
        assertEquals(1, countExc("junit-emp-sa", d, "LATE"), "幂等重跑不重复");
    }

    @Test
    void appealDeadlineIntercepted() {
        LocalDate d = LocalDate.now().minusDays(30); // 逾期日期（相对今日 >5 工作日）
        seedScheduledEmp("ap", d);
        // 造一条已存在的 IDENTIFIED 异常（模拟识别产物，日期久远超 5 工作日）
        jdbc.update("INSERT INTO hr_attendance_exception (ID, EMP_ID, EXC_DATE, EXC_TYPE, STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('junit-exc-ap','junit-emp-ap',?, 'LATE', "
                + "'IDENTIFIED','scan','0',0)", d);
        // 今日申诉超期（工作日远超 ATTEND_APPEAL_DAYS=5）→ 逾期不入状态机
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("ids", List.of("junit-exc-ap"));
        p.put("reason", "测试申诉");
        Map<String, Object> out = attendanceService.batchAppeal(p);
        assertEquals(0, ((Number) out.get("appealed")).intValue(), "逾期拦截");
        assertEquals(1, ((List<?>) out.get("expiredIds")).size());
    }

    @Test
    void mgrConfirmAndHrFinalLock() {
        LocalDate d = LocalDate.of(2099, 8, 5);
        seedScheduledEmp("fc", d);
        jdbc.update("INSERT INTO hr_attendance_exception (ID, EMP_ID, EXC_DATE, EXC_TYPE, STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('junit-exc-fc','junit-emp-fc',?, 'LATE', "
                + "'APPEALED','junit','0',0)", d);
        // HR 代确认留痕（无主管放行 D5）
        Map<String, Object> confirm = new java.util.LinkedHashMap<>();
        confirm.put("approveIds", List.of("junit-exc-fc"));
        attendanceService.batchMgrConfirm(confirm);
        assertEquals("MGR_CONFIRMED", jdbc.queryForObject(
                "SELECT STATUS FROM hr_attendance_exception WHERE ID='junit-exc-fc'", String.class));
        // 终审 → 锁定
        Map<String, Object> fin = new java.util.LinkedHashMap<>();
        fin.put("period", "209908");
        Map<String, Object> out = attendanceService.hrFinalize(fin);
        assertTrue(((Number) out.get("finalized")).intValue() >= 1);
        assertEquals("HR_FINAL", jdbc.queryForObject(
                "SELECT STATUS FROM hr_attendance_exception WHERE ID='junit-exc-fc'", String.class));
        Integer locked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_attendance_month_lock WHERE PERIOD='209908' AND EMP_ID='junit-emp-fc'",
                Integer.class);
        assertEquals(1, locked, "月度锁置位");
        // 更正 → 解锁
        Map<String, Object> corr = new java.util.LinkedHashMap<>();
        corr.put("exceptionId", "junit-exc-fc");
        corr.put("reason", "误判更正");
        attendanceService.correct(corr);
        assertEquals("CORRECTED", jdbc.queryForObject(
                "SELECT STATUS FROM hr_attendance_exception WHERE ID='junit-exc-fc'", String.class));
        assertNotNull(jdbc.queryForObject(
                "SELECT CORRECT_AT FROM hr_attendance_month_lock WHERE PERIOD='209908' "
                        + "AND EMP_ID='junit-emp-fc'", Object.class), "解锁时间留痕");
    }

    @Test
    void makeupPunchClosesMissingAndRescan() {
        LocalDate d = LocalDate.of(2099, 9, 9);
        seedScheduledEmp("mk", d);
        scanTask.scanForDate(d); // 全天无打卡 → ABSENT（零记录=旷工；缺卡=部分打卡）
        assertTrue(countExc("junit-emp-mk", d, "ABSENT") >= 1, "补卡前旷工存在");
        // 提交补卡 IN
        Map<String, Object> sub = new java.util.LinkedHashMap<>();
        sub.put("empId", "junit-emp-mk");
        sub.put("makeupDate", d.toString());
        sub.put("dir", "IN");
        sub.put("reason", "忘记打卡");
        var m = makeupService.submit(sub);
        // 主管确认 + HR 审核通过
        makeupService.mgrConfirm(m.getId(), Map.of("opinion", "确认"));
        makeupService.hrReview(m.getId(), Map.of("opinion", "通过"));
        // 打卡已补 + 缺卡异常关闭
        Integer punchCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_punch_record WHERE EMP_ID='junit-emp-mk' AND PUNCH_DATE=? "
                        + "AND SOURCE='MANUAL'", Integer.class, d);
        assertEquals(1, punchCnt, "MANUAL 打卡落库");
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM hr_makeup_punch WHERE ID=?", String.class, m.getId()));
        // 重识别后旷工消除（有 IN 打卡）；OUT 仍缺 → 重建 MISSING（准确态，可再补 OUT）
        assertEquals(0, countExc("junit-emp-mk", d, "ABSENT"), "重识别后旷工消除");
    }

    @Test
    void monthlySummaryPendingHold() {
        LocalDate d = LocalDate.of(2099, 10, 10);
        seedScheduledEmp("hs", d);
        jdbc.update("INSERT INTO hr_attendance_exception (ID, EMP_ID, EXC_DATE, EXC_TYPE, STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('junit-exc-hs','junit-emp-hs',?, 'LATE', "
                + "'IDENTIFIED','scan','0',0)", d);
        // 未处理完 → 汇总行挂起标记
        List<Map<String, Object>> rows = attendanceService.monthlySummary("209910");
        Map<String, Object> row = rows.stream()
                .filter(r -> "junit-emp-hs".equals(r.get("empId"))).findFirst().orElse(null);
        assertNotNull(row, "汇总行存在");
        assertEquals(Boolean.TRUE, row.get("pendingHold"), "C-4.7-01 挂起标记");
        // 终审前有 IDENTIFIED → hrFinalize 应被阻断
        Map<String, Object> fin = new java.util.LinkedHashMap<>();
        fin.put("period", "209910");
        com.erp.common.ServiceException e = org.junit.jupiter.api.Assertions.assertThrows(
                com.erp.common.ServiceException.class, () -> attendanceService.hrFinalize(fin));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("C-4.7-01"), e.getMessage());
    }

    private int countExc(String empId, LocalDate date, String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_attendance_exception WHERE EMP_ID=? AND EXC_DATE=? AND EXC_TYPE=? "
                        + "AND DEL_FLAG='0'", Integer.class, empId, date, type);
    }
}
