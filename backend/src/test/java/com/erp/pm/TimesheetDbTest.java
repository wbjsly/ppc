package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmTimesheetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 工时填报 DbTest（timesheet-management spec：0.5h 步长 / 24h 拦截 / UK 同人同天 /
 * BR-4.8-18 任务级校验 / BR-4.8-17 补录两档）。
 * 造数前缀 tst- / 项目名 TST%。empId = 登录账号（principal name）。
 */
@SpringBootTest
class TimesheetDbTest {

    @Autowired
    private PmTimesheetService timesheetService;
    @Autowired
    private JdbcTemplate jdbc;

    private String phaseId;
    private String taskId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("tst-emp", "tstemp", "工时测试员工");
        seedUser("tst-owner", "tstowner", "工时测试负责人");
        seedUser("tst-pm", "tstpm", "工时测试 PM");
        seedProject();
        phaseId = seedWbs(1, "TST 阶段", null, null, null);
        taskId = seedWbs(3, "TST 任务", phaseId, "tst-owner", "JUNIOR");
        authAs("tst-emp", "USER");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---------- 填报校验（6.1） ----------

    @Test
    void halfStepAndContentRequired() {
        ServiceException e1 = assertThrows(ServiceException.class, () ->
                timesheetService.save(Map.of("workDate", today(), "lines",
                        List.of(Map.of("wbsId", taskId, "hours", 0.7, "workContent", "x")))));
        assertTrue(e1.getMessage().contains("0.5"), e1.getMessage());
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                timesheetService.save(Map.of("workDate", today(), "lines",
                        List.of(Map.of("wbsId", taskId, "hours", 2)))));
        assertEquals(422, e2.getCode());
        ServiceException e3 = assertThrows(ServiceException.class, () ->
                timesheetService.save(Map.of("workDate", today(), "lines",
                        List.of(Map.of("wbsId", taskId, "hours", 1, "workContent", "")))));
        assertEquals(422, e3.getCode());
    }

    @Test
    void dailyCapAndLevelTaskAndBr18() {
        // 24h 合计拦截（23 + 2 = 25）
        timesheetService.save(Map.of("workDate", today(), "lines", List.of(
                Map.of("wbsId", taskId, "hours", 23, "workContent", "x"))));
        ServiceException e1 = assertThrows(ServiceException.class, () ->
                timesheetService.save(Map.of("id", String.valueOf(currentSheetId()),
                        "workDate", today(), "lines", List.of(
                        Map.of("wbsId", taskId, "hours", 23, "workContent", "x"),
                        Map.of("wbsId", taskId, "hours", 2, "workContent", "y")))));
        assertTrue(e1.getMessage().contains("24"), e1.getMessage());
        // BR-4.8-18：未关联任务（走更新既有今日单）
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                timesheetService.save(Map.of("id", String.valueOf(currentSheetId()),
                        "workDate", today(), "lines",
                        List.of(Map.of("hours", 1, "workContent", "x")))));
        assertTrue(e2.getMessage().contains("WBS 任务"), e2.getMessage());
        // 非任务级（阶段节点；用过去日期避开未来日期拦截）
        ServiceException e3 = assertThrows(ServiceException.class, () ->
                timesheetService.save(Map.of("workDate", LocalDate.now().minusDays(3).toString(), "lines",
                        List.of(Map.of("wbsId", phaseId, "hours", 1, "workContent", "x")))));
        assertTrue(e3.getMessage().contains("任务级"), e3.getMessage());
    }

    @Test
    void ukSameEmpSameDay() {
        timesheetService.save(Map.of("workDate", today(), "lines",
                List.of(Map.of("wbsId", taskId, "hours", 1, "workContent", "x"))));
        ServiceException e = assertThrows(ServiceException.class, () ->
                timesheetService.save(Map.of("workDate", today(), "lines",
                        List.of(Map.of("wbsId", taskId, "hours", 2, "workContent", "y")))));
        assertEquals(409, e.getCode());
    }

    // ---------- 补录两档（BR-4.8-17） ----------

    @Test
    void backfillTiers() {
        LocalDate thisMonday = LocalDate.now().with(DayOfWeek.MONDAY);
        // 本周内：正常提交
        LocalDate thisWeekDate = thisMonday.plusDays(1).isAfter(LocalDate.now())
                ? LocalDate.now() : thisMonday.plusDays(1);
        Map<String, Object> thisWeek = createAndSubmit(thisWeekDate);
        assertEquals("SUBMITTED", thisWeek.get("status"));
        // 上周：正常提交 + 补录标
        Map<String, Object> lastWeek = createAndSubmit(thisMonday.minusDays(3));
        assertEquals("SUBMITTED", lastWeek.get("status"));
        assertEquals("1", lastWeek.get("isBackfill"));
        // 超 2 周（3 周前）：BACKFILL + 自动发起 PMO 审批
        Map<String, Object> old = createAndSubmit(thisMonday.minusWeeks(3));
        assertEquals("BACKFILL", old.get("status"));
        assertNotNull(old.get("backfillApprId"));
        // 审批通过 → SUBMITTED 进审核队列
        callback().onApproved(loadInstance(String.valueOf(old.get("backfillApprId"))));
        assertEquals("SUBMITTED", statusOf(String.valueOf(old.get("id"))));
        // 超 4 周：save 允许（草稿），submit 拦 422
        Map<String, Object> far = timesheetService.save(Map.of("workDate", thisMonday.minusWeeks(5).toString(),
                "lines", List.of(Map.of("wbsId", taskId, "hours", 1, "workContent", "x"))));
        final String farFinal = String.valueOf(far.get("id"));
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                timesheetService.submit(farFinal));
        assertEquals(422, e2.getCode());
    }

    // ---------- helpers ----------

    private Map<String, Object> createAndSubmit(LocalDate date) {
        Map<String, Object> saved = timesheetService.save(Map.of("workDate", date.toString(), "lines",
                List.of(Map.of("wbsId", taskId, "hours", 2, "workContent", "补录测试"))));
        return timesheetService.submit(String.valueOf(saved.get("id")));
    }

    private Object currentSheetId() {
        return jdbc.queryForObject("SELECT ID FROM erp_pm_timesheet WHERE EMP_ID='tst-emp' AND WORK_DATE=?",
                Object.class, LocalDate.now());
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_pm_timesheet WHERE ID=?", String.class, id);
    }

    private com.erp.service.impl.pm.PmTimesheetApprovalCallback callback() {
        return ctx.getBean(com.erp.service.impl.pm.PmTimesheetApprovalCallback.class);
    }

    @Autowired
    private org.springframework.context.ApplicationContext ctx;

    private com.erp.entity.system.ApprovalInstance loadInstance(String apprId) {
        return jdbc.queryForObject("SELECT * FROM erp_sys_approval WHERE ID=?",
                (rs, i) -> {
                    com.erp.entity.system.ApprovalInstance inst = new com.erp.entity.system.ApprovalInstance();
                    inst.setId(rs.getString("ID"));
                    inst.setBizType(rs.getString("BIZ_TYPE"));
                    inst.setBizId(rs.getString("BIZ_ID"));
                    inst.setTitle(rs.getString("TITLE"));
                    inst.setStatus(rs.getString("STATUS"));
                    return inst;
                }, apprId);
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    private static String tomorrow() {
        return LocalDate.now().plusDays(1).toString();
    }

    private void seedProject() {
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('tst-prj-1', 'TST 工时项目', '研发', 'INTERNAL', 'NS-TST', 'tst-pm', "
                + "'2026-10-01', '2027-06-30', 'ACTIVE', '0', 0, 0.10, '0', 'junit', NOW(), '0', 0)");
    }

    private String seedWbs(int level, String name, String parentId, String owner, String grade) {
        String id = "tst-wbs-" + level + "-" + name.hashCode() % 1000;
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, PARENT_ID, LEVEL, NAME, "
                + "OWNER_USER_ID, OWNER_GRADE, EST_HOURS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'tst-prj-1', ?, ?, ?, ?, ?, ?, 40, 'junit', NOW(), '0', 0)",
                id, "W" + level + id.substring(7, 11), parentId, level, name, owner, grade);
        return id;
    }

    private void seedUser(String id, String username, String nick) {
        jdbc.update("INSERT INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'x', ?, '1', 'junit', NOW(), '0', 0)",
                id, username, nick);
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, "x",
                java.util.Arrays.stream(roles)
                        .map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                        .toList()));
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE='PmBackfill' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_timesheet WHERE EMP_ID LIKE 'tst-%') t2)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmBackfill' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_timesheet WHERE EMP_ID LIKE 'tst-%') t)");
        jdbc.update("DELETE FROM erp_pm_hours_collect WHERE TIMESHEET_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_timesheet WHERE EMP_ID LIKE 'tst-%') t)");
        jdbc.update("DELETE FROM erp_pm_timesheet_line WHERE TIMESHEET_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_timesheet WHERE EMP_ID LIKE 'tst-%') t)");
        jdbc.update("DELETE FROM erp_pm_timesheet WHERE EMP_ID LIKE 'tst-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID='tst-prj-1'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID='tst-prj-1'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID IN ('tst-emp','tst-owner','tst-pm')");
    }
}
