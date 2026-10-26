package com.erp.pm;

import com.erp.service.pm.PmMilestoneService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 进度看板与预警扫描 DbTest（milestone-management spec：完成率 / 偏差工作日 /
 * L4 与升级分级 / 三类扫描幂等）。
 * 造数前缀 tpg- / 项目名 TPG%。
 */
@SpringBootTest
class ProgressDbTest {

    @Autowired
    private PmMilestoneService milestoneService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String phaseId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("tpg-user-pm", "tpgpm", "TPG 项目经理");
        projectId = seedProject();
        phaseId = seedWbsPhase();
        authAs("tpg-junit", "PMO");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void completionRate() {
        // 4 个里程碑 1 个 DONE → 完成率 25%
        seedMilestone("tpg-ms-1", "DONE", LocalDate.now().minusDays(10), LocalDate.now().minusDays(9));
        seedMilestone("tpg-ms-2", "PLANNED", LocalDate.now().plusDays(10), null);
        seedMilestone("tpg-ms-3", "PLANNED", LocalDate.now().plusDays(20), null);
        seedMilestone("tpg-ms-4", "REWORK", LocalDate.now().plusDays(30), null);
        Map<String, Object> board = milestoneService.progress(projectId);
        assertEquals(4, board.get("total"));
        assertEquals(1, board.get("done"));
        assertEquals(0, new java.math.BigDecimal("25.00").compareTo(
                new java.math.BigDecimal(String.valueOf(board.get("rate")))), "完成率 25%");
    }

    @Test
    void deviationWorkdays() {
        // 周一完成、上周一计划 → 5 个工作日偏差（DONE 用实际完成日）
        LocalDate plan = LocalDate.now().minusWeeks(1).with(java.time.DayOfWeek.MONDAY);
        LocalDate actual = plan.plusDays(7); // 下周一，跨 5 个工作日
        seedMilestone("tpg-dev-1", "DONE", plan, actual);
        // 未完成用 today 锚点
        seedMilestone("tpg-dev-2", "PLANNED", LocalDate.now().plusDays(3), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) milestoneService.progress(projectId).get("rows");
        Map<String, Object> done = rows.stream()
                .filter(r -> "tpg-dev-1".equals(String.valueOf(r.get("id")))).findFirst().orElseThrow();
        assertEquals(5, done.get("deviationWorkdays"), "DONE 里程碑偏差=5 工作日");
        assertTrue((Boolean) done.get("overdue"));
        Map<String, Object> planned = rows.stream()
                .filter(r -> "tpg-dev-2".equals(String.valueOf(r.get("id")))).findFirst().orElseThrow();
        // 未到期：today 锚点早于计划日 → 负偏差（正数=滞后语义），不计超期
        assertTrue(((Number) planned.get("deviationWorkdays")).intValue() < 0, "未到期为负偏差");
        assertEquals(false, planned.get("overdue"));
    }

    @Test
    void delayedScanLevelsAndIdempotency() {
        // 7 工作日前计划 → L4；16 工作日前计划 → 升级
        LocalDate lag7 = minusWorkdays(7);
        LocalDate lag16 = minusWorkdays(16);
        seedMilestone("tpg-l4", "PLANNED", lag7, null);
        seedMilestone("tpg-esc", "PLANNED", lag16, null);
        // 审批超时：APPROVED 且评审通过 4 工作日前
        String od = seedMilestone("tpg-od", "APPROVED", LocalDate.now().plusDays(10), null);
        jdbc.update("UPDATE erp_pm_milestone SET REVIEW_PASS_AT=? WHERE ID=?",
                java.sql.Timestamp.valueOf(minusWorkdays(4).atStartOfDay()), od);

        Map<String, Object> out = milestoneService.dailyScan();
        assertEquals(1, out.get("delayL4"), "L4 一条");
        assertEquals(1, out.get("escalated"), "升级一条");
        assertEquals(1, out.get("approveOverdue"), "审批超时一条");
        // 标记落库
        assertEquals("1", flagOf("tpg-l4", "DELAY_NOTIFIED"));
        assertEquals("1", flagOf("tpg-esc", "ESCALATED_NOTIFIED"));
        assertEquals("1", flagOf("tpg-od", "OVERDUE_REMINDED"));
        // 升级里程碑同时置 L4 标记（防重复通知）
        assertEquals("1", flagOf("tpg-esc", "DELAY_NOTIFIED"));
        // hr_remind 落库（UK 防重语义）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='MS_APPROVE_OVERDUE' AND EMP_ID=?",
                Integer.class, od));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_remind WHERE REMIND_TYPE='MS_DELAY' AND EMP_ID=?",
                Integer.class, "tpg-esc"));
        // 幂等：重复扫描零新增
        Map<String, Object> out2 = milestoneService.dailyScan();
        assertEquals(0, out2.get("delayL4"));
        assertEquals(0, out2.get("escalated"));
        assertEquals(0, out2.get("approveOverdue"));
    }

    @Test
    void doneMilestoneExcludedFromScan() {
        // DONE 里程碑即使过期也不进滞后扫描
        seedMilestone("tpg-done-lag", "DONE", minusWorkdays(20), LocalDate.now().minusDays(18));
        Map<String, Object> out = milestoneService.dailyScan();
        assertEquals(0, out.get("delayL4"));
        assertEquals(0, out.get("escalated"));
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

    private String seedMilestone(String id, String status, LocalDate planEnd, LocalDate actual) {
        jdbc.update("INSERT INTO erp_pm_milestone (ID, PROJECT_ID, WBS_ID, NAME, PLAN_END_DATE, "
                + "STATUS, REWORK_COUNT, ACTUAL_END_DATE, DELAY_NOTIFIED, ESCALATED_NOTIFIED, "
                + "OVERDUE_REMINDED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, '0', '0', '0', 'junit', NOW(), '0', 0)",
                id, projectId, phaseId, "TPG " + id, planEnd, status, actual);
        return id;
    }

    private String flagOf(String id, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM erp_pm_milestone WHERE ID=?",
                String.class, id);
    }

    private String seedProject() {
        String id = "tgp-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'TPG 进度项目', '研发', 'INTERNAL', 'NS-TPG', 'tpg-user-pm', "
                + "'2026-10-01', '2027-06-30', 'ACTIVE', '0', 0, 0.10, '0', 'junit', NOW(), '0', 0)",
                id);
        return id;
    }

    private String seedWbsPhase() {
        String id = "tgp-wbs-phase";
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'W1PG', 1, 'TPG 阶段', "
                + "'junit', NOW(), '0', 0)", id, projectId);
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
        jdbc.update("DELETE FROM erp_pm_ms_review WHERE MILESTONE_ID LIKE 'tpg-%'");
        jdbc.update("DELETE FROM erp_pm_milestone WHERE ID LIKE 'tpg-%' OR PROJECT_ID LIKE 'tgp-%'");
        jdbc.update("DELETE FROM hr_remind WHERE REMIND_TYPE IN ('MS_APPROVE_OVERDUE','MS_DELAY') "
                + "AND EMP_ID LIKE 'tpg-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmMilestone%' "
                + "AND (BIZ_ID LIKE 'tpg-%' OR TITLE LIKE '%TPG%')");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE ID LIKE 'tgp-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'tgp-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='tpg-user-pm'");
    }
}
