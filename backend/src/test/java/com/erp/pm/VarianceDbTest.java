package com.erp.pm;

import com.erp.service.pm.PmHoursVarianceService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 工时偏差 DbTest（timesheet-management spec：偏差率 / 黄红分级 / 5 工作日升级 /
 * 已处理收口 / 分母 0 跳过 / 提醒幂等）。
 * 造数前缀 tva- / 项目名 TVA%。
 */
@SpringBootTest
class VarianceDbTest {

    @Autowired
    private PmHoursVarianceService varianceService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("tva-pm", "tvapm", "偏差测试 PM");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void deviationYellowRedLevels() {
        // 项目 P1：预估 100h，归集 112h → +12% 黄色
        seedProject("tva-prj-y", "TVA 黄项目", "tva-pm");
        seedTask("tva-task-y", "tva-prj-y", 100);
        seedCollect("tva-col-y", "tva-prj-y", "tva-task-y", 112);
        // 项目 P2：预估 100h，归集 125h → +25% 红色
        seedProject("tva-prj-r", "TVA 红项目", "tva-pm");
        seedTask("tva-task-r", "tva-prj-r", 100);
        seedCollect("tva-col-r", "tva-prj-r", "tva-task-r", 125);
        // 项目 P3：预估 0 → 分母 0 跳过
        seedProject("tva-prj-z", "TVA 零预估项目", "tva-pm");
        seedTask("tva-task-z", "tva-prj-z", 0);

        Map<String, Object> out = varianceService.dailyScan();
        assertEquals(1, out.get("yellow"), "黄色一条");
        assertEquals(1, out.get("red"), "红色一条");
        // 幂等：重扫不重复生成
        Map<String, Object> out2 = varianceService.dailyScan();
        assertEquals(0, out2.get("yellow"));
        assertEquals(0, out2.get("red"));
        // 预警落库
        assertEquals("YELLOW", jdbc.queryForObject(
                "SELECT LEVEL FROM erp_pm_hours_alert WHERE PROJECT_ID='tva-prj-y'", String.class));
        assertEquals("RED", jdbc.queryForObject(
                "SELECT LEVEL FROM erp_pm_hours_alert WHERE PROJECT_ID='tva-prj-r'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_hours_alert WHERE PROJECT_ID='tva-prj-z'", Integer.class),
                "分母 0 不生成预警");
        // 通知落库（黄→PM / 红→PM+PMO）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmHoursAlert' AND BIZ_ID='tva-prj-y:YELLOW'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmHoursAlertPmo' AND BIZ_ID='tva-prj-r:RED'",
                Integer.class));
    }

    @Test
    void handledClosesEscalation() {
        seedProject("tva-prj-h", "TVA 已处理项目", "tva-pm");
        seedTask("tva-task-h", "tva-prj-h", 100);
        seedCollect("tva-col-h", "tva-prj-h", "tva-task-h", 130);
        varianceService.dailyScan();
        String alertId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_hours_alert WHERE PROJECT_ID='tva-prj-h'", String.class);
        // PM 标记已处理 → 不再升级
        authAs("tva-pm", "PM");
        Map<String, Object> handled = varianceService.handleAlert(alertId, "已调整后续估算");
        assertEquals(true, handled.get("handled"));
        // 人为把预警日期改早 6 工作日，模拟超 5 工作日
        jdbc.update("UPDATE erp_pm_hours_alert SET ALERT_DATE=? WHERE ID=?",
                minusWorkdays(6), alertId);
        Map<String, Object> out = varianceService.dailyScan();
        assertEquals(0, out.get("escalated"), "已处理不升级");
        // 幂等：重复标记
        Map<String, Object> again = varianceService.handleAlert(alertId, "重复");
        assertEquals(true, again.get("handled"));
    }

    @Test
    void escalateAfterFiveWorkdays() {
        seedProject("tva-prj-e", "TVA 升级项目", "tva-pm");
        seedTask("tva-task-e", "tva-prj-e", 100);
        seedCollect("tva-col-e", "tva-prj-e", "tva-task-e", 140);
        varianceService.dailyScan();
        String alertId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_hours_alert WHERE PROJECT_ID='tva-prj-e'", String.class);
        // 未处理 + 预警日期 6 工作日前 → 升级
        jdbc.update("UPDATE erp_pm_hours_alert SET ALERT_DATE=? WHERE ID=?", minusWorkdays(6), alertId);
        Map<String, Object> out = varianceService.dailyScan();
        assertEquals(1, out.get("escalated"));
        assertEquals("1", jdbc.queryForObject(
                "SELECT ESCALATED_FLAG FROM erp_pm_hours_alert WHERE ID=?", String.class, alertId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmHoursEscalate' AND BIZ_ID=?",
                Integer.class, alertId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='HOURS_ESCALATE' AND EMP_ID='tva-prj-e'",
                Integer.class));
        // 幂等：重扫不重复升级
        Map<String, Object> out2 = varianceService.dailyScan();
        assertEquals(0, out2.get("escalated"));
    }

    @Test
    void varianceViewRows() {
        seedProject("tva-prj-v", "TVA 视图项目", "tva-pm");
        seedTask("tva-task-v", "tva-prj-v", 80);
        seedCollect("tva-col-v", "tva-prj-v", "tva-task-v", 90);
        Map<String, Object> view = varianceService.variance("tva-prj-v");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects = (List<Map<String, Object>>) view.get("projects");
        assertEquals(1, projects.size());
        Map<String, Object> p = projects.get(0);
        assertEquals(0, new BigDecimal("12.50").compareTo(new BigDecimal(String.valueOf(p.get("deviationRate")))),
                "(90-80)/80 = 12.5%");
        assertEquals("YELLOW", p.get("level"));
    }

    // ---------- helpers ----------

    private static LocalDate minusWorkdays(int n) {
        LocalDate d = LocalDate.now();
        int c = 0;
        while (c < n) {
            d = d.minusDays(1);
            if (d.getDayOfWeek().getValue() < 6) {
                c++;
            }
        }
        return d;
    }

    private void seedProject(String id, String name, String pm) {
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, '研发', 'INTERNAL', 'NS-TVA', ?, '2026-10-01', '2027-06-30', "
                + "'ACTIVE', '0', 0, 0.10, '0', 'junit', NOW(), '0', 0)", id, name, pm);
    }

    private void seedTask(String id, String projectId, int estHours) {
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, OWNER_USER_ID, "
                + "OWNER_GRADE, EST_HOURS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, 3, 'TVA 任务', 'tva-pm', 'MID', ?, 'junit', NOW(), '0', 0)",
                id, projectId, "W3" + id, estHours);
    }

    private void seedCollect(String id, String projectId, String wbsId, int hours) {
        jdbc.update("INSERT INTO erp_pm_hours_collect (ID, TIMESHEET_ID, LINE_ID, EMP_ID, EMP_NAME, "
                + "GRADE, GRADE_SOURCE, PROJECT_ID, WBS_ID, WORK_DATE, HOURS, RATE, LABOR_COST, "
                + "COLLECT_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, 'tva-emp', 'TVA 员工', 'MID', 'TASK', ?, ?, ?, ?, 100, ?, ?, "
                + "'junit', NOW(), '0', 0)",
                id, "tva-ts-" + id, "tva-line-" + id, projectId, wbsId,
                LocalDate.now().minusDays(2), hours, java.math.BigDecimal.valueOf(hours * 100L),
                LocalDate.now());
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
        jdbc.update("DELETE FROM erp_pm_hours_alert WHERE PROJECT_ID LIKE 'tva-prj-%'");
        jdbc.update("DELETE FROM erp_pm_hours_collect WHERE PROJECT_ID LIKE 'tva-prj-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'tva-prj-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'tva-prj-%'");
        jdbc.update("DELETE FROM hr_remind WHERE REMIND_TYPE='HOURS_ESCALATE' AND EMP_ID LIKE 'tva-prj-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('PmHoursAlert','PmHoursAlertPmo','PmHoursEscalate') "
                + "AND (BIZ_ID LIKE 'tva-prj-%' OR TITLE LIKE '%TVA%')");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='tva-pm'");
    }
}
