package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.entity.pm.PmProject;
import com.erp.service.pm.PmProjectService;
import com.erp.service.pm.PmResourceService;
import com.erp.service.pm.PmWbsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 资源产能 DbTest（project-initiation spec：负载汇总/超产能警示/
 * 跨项目重叠/产能日历默认值）。
 * 造数前缀 trs- / 项目名 TRS%。
 */
@SpringBootTest
class ResourceDbTest {

    @Autowired
    private PmResourceService resourceService;
    @Autowired
    private PmProjectService projectService;
    @Autowired
    private PmWbsService wbsService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 下一个周一（保证落在工作日） */
    private LocalDate monday;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("trs-junit", "TPI 资源 PMO");
        authAs("trs-junit", "ROLE_PMO");
        LocalDate d = LocalDate.now().plusDays(14);
        while (d.getDayOfWeek() != DayOfWeek.MONDAY) {
            d = d.plusDays(1);
        }
        monday = d;
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void seedUser(String userId, String nick) {
        jdbc.update("INSERT INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'x', ?, '1', 'junit', NOW(), '0', 0)",
                userId, userId, nick);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='trs-junit'");
        jdbc.update("DELETE FROM erp_pm_capacity WHERE USER_ID LIKE 'trs-%'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TRS%')");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TRS%')");
        jdbc.update("DELETE FROM erp_pm_project WHERE NAME LIKE 'TRS%'");
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(roles)));
    }

    private String seedProject(String name) {
        PmProject p = projectService.create(Map.of("name", name, "projType", "研发",
                "deliveryPath", "INTERNAL", "internalFormNo", "IF-" + name,
                "pmUserId", "trs-junit", "startDate", monday.toString(),
                "endDate", monday.plusMonths(6).toString()));
        return p.getId();
    }

    private void seedTask(String projectId, String owner, String grade, int hours,
                          LocalDate start, LocalDate end) {
        Map<String, Object> phase = wbsService.create(Map.of(
                "projectId", projectId, "level", 1, "name", "阶段-" + owner));
        Map<String, Object> pkg = wbsService.create(Map.of(
                "projectId", projectId, "level", 2, "parentId", phase.get("id"),
                "name", "工作包-" + owner, "deliverable", "D"));
        wbsService.create(Map.of("projectId", projectId, "level", 3, "parentId", pkg.get("id"),
                "name", "任务-" + owner, "ownerUserId", owner, "ownerGrade", grade,
                "planStart", start.toString(), "planEnd", end.toString(), "estHours", hours));
    }

    @Test
    void loadAggregatesDailyAndWarnsOverCapacity() {
        String pid = seedProject("TRS 单项目");
        // 周一~周五 40h → 日均 8h，恰好等于默认产能，不警示
        seedTask(pid, "trs-a", "MID", 40, monday, monday.plusDays(4));
        List<Map<String, Object>> view = resourceService.loadView(
                monday.toString(), monday.plusDays(6).toString());
        Map<String, Object> userA = findUser(view, "trs-a");
        Map<String, Object> monRow = findDay(userA, monday.toString());
        assertEquals(0, new BigDecimal("8").compareTo(new BigDecimal(
                String.valueOf(monRow.get("loadHours")))), "日均=40/5");
        assertEquals(0, new BigDecimal("8").compareTo(new BigDecimal(
                String.valueOf(monRow.get("capacityHours")))), "默认工作日产能 8");
        assertFalse((Boolean) monRow.get("warning"), "恰好等于产能不警示");
        // 周六默认产能 0：若任务跨周末则警示
        seedTask(pid, "trs-b", "MID", 20, monday, monday.plusDays(6)); // 7 天 20h 日均约 2.86
        view = resourceService.loadView(monday.toString(), monday.plusDays(6).toString()); // 重取快照
        Map<String, Object> userB = findUser(view, "trs-b");
        Map<String, Object> satRow = findDay(userB, monday.plusDays(5).toString());
        assertEquals(0, new BigDecimal("0").compareTo(new BigDecimal(
                String.valueOf(satRow.get("capacityHours")))), "周末默认产能 0");
        assertTrue((Boolean) satRow.get("warning"), "周末有负载 → 警示");
        // 超产能：12h 任务 → 日均 12 > 8
        seedTask(pid, "trs-c", "SENIOR", 60, monday, monday.plusDays(4));
        view = resourceService.loadView(monday.toString(), monday.plusDays(6).toString());
        Map<String, Object> userC = findUser(view, "trs-c");
        assertTrue((Boolean) userC.get("warning"), "超产能用户级警示");
        assertEquals("超产能", userC.get("warningReason"));
    }

    @Test
    void crossProjectOverlapWarns() {
        String p1 = seedProject("TRS 项目甲");
        String p2 = seedProject("TRS 项目乙");
        seedTask(p1, "trs-x", "MID", 10, monday, monday.plusDays(4));
        seedTask(p2, "trs-x", "MID", 10, monday, monday.plusDays(4));
        List<Map<String, Object>> view = resourceService.loadView(
                monday.toString(), monday.plusDays(6).toString());
        Map<String, Object> userX = findUser(view, "trs-x");
        assertTrue((Boolean) userX.get("warning"), "跨项目重叠警示");
        assertEquals("跨项目重叠", userX.get("warningReason"));
        Map<String, Object> monRow = findDay(userX, monday.toString());
        @SuppressWarnings("unchecked")
        List<String> projects = (List<String>) monRow.get("projectIds");
        assertEquals(2, projects.stream().distinct().count(), "双项目标记");
    }

    @Test
    void capacityCalendarOverridesDefault() {
        String pid = seedProject("TRS 产能覆盖");
        seedTask(pid, "trs-d", "MID", 40, monday, monday.plusDays(4));
        // 周一产能压到 4h → 日均 8 超产能
        resourceService.saveCapacity(Map.of("userId", "trs-d",
                "capDate", monday.toString(), "hours", "4"));
        List<Map<String, Object>> view = resourceService.loadView(
                monday.toString(), monday.plusDays(4).toString());
        Map<String, Object> userD = findUser(view, "trs-d");
        Map<String, Object> monRow = findDay(userD, monday.toString());
        assertEquals(0, new BigDecimal("4").compareTo(new BigDecimal(
                String.valueOf(monRow.get("capacityHours")))), "显式产能覆盖默认");
        assertTrue((Boolean) monRow.get("warning"), "低于负载 → 警示");
        // 产能行可查
        List<Map<String, Object>> caps = resourceService.capacityList("trs-d",
                monday.toString(), monday.plusDays(4).toString());
        assertEquals(1, caps.size());
        // 非法工时拒绝
        ServiceException e = assertThrows(ServiceException.class, () ->
                resourceService.saveCapacity(Map.of("userId", "trs-d",
                        "capDate", monday.toString(), "hours", "25")));
        assertEquals(422, e.getCode());
    }

    @Test
    void spanLimitEnforced() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                resourceService.loadView(monday.toString(), monday.plusDays(400).toString()));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("跨度"), e.getMessage());
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                resourceService.loadView(monday.plusDays(3).toString(), monday.toString()));
        assertEquals(422, e2.getCode());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findUser(List<Map<String, Object>> view, String userId) {
        return view.stream()
                .filter(u -> userId.equals(u.get("userId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("负载视图缺用户 " + userId + "：" + view));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findDay(Map<String, Object> user, String date) {
        List<Map<String, Object>> days = (List<Map<String, Object>>) user.get("days");
        return days.stream()
                .filter(d -> date.equals(d.get("date")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("负载视图缺日期 " + date));
    }
}
