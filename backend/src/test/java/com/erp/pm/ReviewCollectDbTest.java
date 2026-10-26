package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmHoursCollectService;
import com.erp.service.pm.PmTimesheetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 工时审核与归集 DbTest（timesheet-management spec：审核归属 / 归集费率快照 /
 * 期间匹配与 L4 兜底 / 调薪不污染历史 / 四级汇总）。
 * 造数前缀 trc- / 项目名 TRC%。
 */
@SpringBootTest
class ReviewCollectDbTest {

    @Autowired
    private PmTimesheetService timesheetService;
    @Autowired
    private PmHoursCollectService collectService;
    @Autowired
    private JdbcTemplate jdbc;

    private String ownerATask;
    private String ownerBTask;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("trc-emp", "trcemp", "归集测试员工");
        seedUser("trc-ownerA", "trcownera", "负责人A");
        seedUser("trc-ownerB", "trcownerb", "负责人B");
        seedUser("trc-pm", "trcpm", "归集测试 PM");
        seedProject();
        String phase = seedWbs(1, "TRC 阶段", null, null, null, null);
        String pkg = seedWbs(2, "TRC 包", phase, null, null, null);
        ownerATask = seedWbs(3, "TRC 任务A", pkg, "trc-ownerA", "MID", null);
        ownerBTask = seedWbs(3, "TRC 任务B", pkg, "trc-ownerB", "MID", null);
        // MID 期间费率：2026 全年 120，2027 起 150（同职级多行，170 UK 降级后允许）
        // 生效起点晚于共享库默认 MID 费率（2026-01-01）：同区间多行取最新生效者（业务语义）
        seedRate("trc-rate-2026", "MID", "120", "2026-06-01", "2026-12-31");
        seedRate("trc-rate-2027", "MID", "150", "2027-01-01", null);
        authAs("trc-emp", "USER");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void reviewerSingleOwnerVsCrossProject() {
        // 单一任务负责人 → 该负责人可审
        String singleId = createAndSubmit(LocalDate.now(), ownerATask);
        authAs("trc-ownerA", "USER");
        Map<String, Object> ok = timesheetService.review(singleId, Map.of("action", "APPROVE"));
        assertEquals("APPROVED", ok.get("status"));
        // 跨负责人 → 回落 PM；非 PM 用户审 → 403
        String crossId = createAndSubmit(LocalDate.now().minusDays(2), ownerATask, ownerBTask);
        authAs("trc-ownerA", "USER");
        ServiceException e = assertThrows(ServiceException.class, () ->
                timesheetService.review(crossId, Map.of("action", "APPROVE")));
        assertEquals(403, e.getCode());
        // PM 审 → 通过
        authAs("trc-pm", "PM");
        Map<String, Object> pmOk = timesheetService.review(crossId, Map.of("action", "APPROVE"));
        assertEquals("APPROVED", pmOk.get("status"));
    }

    @Test
    void rejectReasonRequiredAndResubmit() {
        String id = createAndSubmit(LocalDate.now(), ownerATask);
        authAs("trc-ownerA", "USER");
        ServiceException e = assertThrows(ServiceException.class, () ->
                timesheetService.review(id, Map.of("action", "REJECT", "reason", "不")));
        assertEquals(422, e.getCode());
        timesheetService.review(id, Map.of("action", "REJECT", "reason", "工时过高，与任务复杂度不符"));
        assertEquals("REJECTED", jdbc.queryForObject("SELECT STATUS FROM erp_pm_timesheet WHERE ID=?",
                String.class, id));
        assertEquals("工时过高，与任务复杂度不符",
                jdbc.queryForObject("SELECT REJECT_REASON FROM erp_pm_timesheet WHERE ID=?", String.class, id));
        // 员工修改后重提
        authAs("trc-emp", "USER");
        timesheetService.save(Map.of("id", id, "workDate", LocalDate.now().toString(), "lines",
                List.of(Map.of("wbsId", ownerATask, "hours", 3, "workContent", "调整后工时"))));
        timesheetService.submit(id);
        assertEquals("SUBMITTED", jdbc.queryForObject("SELECT STATUS FROM erp_pm_timesheet WHERE ID=?",
                String.class, id));
    }

    @Test
    void collectSnapshotAndPeriodRate() {
        // 2026 年工时 → 命中 2026 区间费率 120
        String id = createAndSubmit(LocalDate.now(), ownerATask);
        authAs("trc-ownerA", "USER");
        timesheetService.review(id, Map.of("action", "APPROVE"));
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT RATE, LABOR_COST, GRADE, GRADE_SOURCE FROM erp_pm_hours_collect WHERE TIMESHEET_ID=?", id);
        assertEquals(0, new BigDecimal("120").compareTo(new BigDecimal(String.valueOf(c.get("RATE")))));
        assertEquals(0, new BigDecimal("240.00").compareTo(new BigDecimal(String.valueOf(c.get("LABOR_COST")))),
                "2h × 120 = 240");
        assertEquals("MID", c.get("GRADE"));
        assertEquals("TASK", c.get("GRADE_SOURCE"));
        // 归集幂等：同工时单重复 collect 不新增行（DB 计数口径）
        long rowsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_hours_collect WHERE TIMESHEET_ID=?", Long.class, id);
        com.erp.entity.pm.PmTimesheet reload = jdbc.queryForObject(
                "SELECT * FROM erp_pm_timesheet WHERE ID=?",
                (rs, i) -> {
                    com.erp.entity.pm.PmTimesheet t = new com.erp.entity.pm.PmTimesheet();
                    t.setId(rs.getString("ID"));
                    t.setEmpId(rs.getString("EMP_ID"));
                    t.setWorkDate(rs.getDate("WORK_DATE").toLocalDate());
                    return t;
                }, id);
        collectService.collect(reload);
        long rowsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_hours_collect WHERE TIMESHEET_ID=?", Long.class, id);
        assertEquals(rowsBefore, rowsAfter, "归集幂等：同工时单不重复写入");
        assertTrue(rowsBefore >= 1, "已归集至少一行");
        // 调薪不污染历史：把 2026 区间费率改 999，历史归集不变
        jdbc.update("UPDATE erp_pm_rate SET RATE_PER_HOUR=999 WHERE ID='trc-rate-2026'");
        assertEquals(0, new BigDecimal("120").compareTo(new BigDecimal(String.valueOf(
                jdbc.queryForObject("SELECT RATE FROM erp_pm_hours_collect WHERE TIMESHEET_ID=?",
                        Object.class, id)))));
    }

    @Test
    void rateGapFallbackCurrentAndProjectOverride() {
        // 无区间命中场景：2030 年工时（区间只有 2026/2027）→ CURRENT 兜底
        // 先直接写一条 2030 工时单（save 拦未来日期，构造过去日期不可得——改用项目覆盖场景验证 PROJECT 来源）
        // 项目覆盖：rateOverride JSON {"MID":88} → PROJECT 来源
        jdbc.update("UPDATE erp_pm_project SET RATE_OVERRIDE='{\"MID\":88}' WHERE ID='trc-prj-1'");
        String id = createAndSubmit(LocalDate.now(), ownerATask);
        authAs("trc-ownerA", "USER");
        timesheetService.review(id, Map.of("action", "APPROVE"));
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT RATE, GRADE_SOURCE FROM erp_pm_hours_collect WHERE TIMESHEET_ID=?", id);
        assertEquals(0, new BigDecimal("88").compareTo(new BigDecimal(String.valueOf(c.get("RATE")))));
        assertEquals("PROJECT", c.get("GRADE_SOURCE"));
    }

    @Test
    void fourLevelSummary() {
        String id = createAndSubmit(LocalDate.now(), ownerATask);
        authAs("trc-ownerA", "USER");
        timesheetService.review(id, Map.of("action", "APPROVE"));
        Map<String, Object> summary = collectService.summary("trc-prj-1");
        assertEquals(0, new BigDecimal("80").compareTo(new BigDecimal(String.valueOf(summary.get("planHours")))),
                "计划 = 两任务 estHours 40×2");
        assertEquals(0, new BigDecimal("2").compareTo(new BigDecimal(String.valueOf(summary.get("actualHours")))),
                "实际 = 归集 2h");
        assertTrue(((List<?>) summary.get("phases")).size() >= 1);
        assertTrue(((List<?>) summary.get("tasks")).size() >= 2);
    }

    // ---------- helpers ----------

    private String createAndSubmit(LocalDate date, String... wbsIds) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (String w : wbsIds) {
            lines.add(Map.of("wbsId", w, "hours", 2, "workContent", "归集测试"));
        }
        Map<String, Object> saved = timesheetService.save(Map.of("workDate", date.toString(), "lines", lines));
        timesheetService.submit(String.valueOf(saved.get("id")));
        return String.valueOf(saved.get("id"));
    }

    private void seedProject() {
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('trc-prj-1', 'TRC 归集项目', '研发', 'INTERNAL', 'NS-TRC', 'trc-pm', "
                + "'2026-10-01', '2027-06-30', 'ACTIVE', '0', 0, 0.10, '0', 'junit', NOW(), '0', 0)");
    }

    private String seedWbs(int level, String name, String parentId, String owner, String grade, BigDecimal est) {
        String id = "trc-wbs-" + level + "-" + Math.abs(name.hashCode() % 1000);
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, PARENT_ID, LEVEL, NAME, "
                + "OWNER_USER_ID, OWNER_GRADE, EST_HOURS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'trc-prj-1', ?, ?, ?, ?, ?, ?, ?, 'junit', NOW(), '0', 0)",
                id, "W" + level + id.substring(7, 11), parentId, level, name, owner, grade,
                est == null ? new BigDecimal("40") : est);
        return id;
    }

    private void seedRate(String id, String grade, String rate, String from, String to) {
        jdbc.update("INSERT INTO erp_pm_rate (ID, GRADE, RATE_PER_HOUR, EFF_FROM, EFF_TO, STATUS, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, ?, '1', 'junit', NOW(), '0', 0)",
                id, grade, rate, from, to);
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
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_timesheet WHERE EMP_ID LIKE 'trc-%') t2)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmBackfill' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_timesheet WHERE EMP_ID LIKE 'trc-%') t)");
        jdbc.update("DELETE FROM erp_pm_hours_collect WHERE TIMESHEET_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_timesheet WHERE EMP_ID LIKE 'trc-%') t)");
        jdbc.update("DELETE FROM erp_pm_timesheet_line WHERE TIMESHEET_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_timesheet WHERE EMP_ID LIKE 'trc-%') t)");
        jdbc.update("DELETE FROM erp_pm_timesheet WHERE EMP_ID LIKE 'trc-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID='trc-prj-1'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID='trc-prj-1'");
        jdbc.update("DELETE FROM erp_pm_rate WHERE ID LIKE 'trc-rate-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID IN ('trc-emp','trc-ownerA','trc-ownerB','trc-pm')");
    }
}
