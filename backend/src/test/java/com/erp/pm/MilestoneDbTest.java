package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmMilestoneService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 里程碑定义与依赖 DbTest（milestone-management spec：定义挂载 / 前序依赖 / 删除门禁）。
 * 造数前缀 tms- / 项目名 TMS%。
 */
@SpringBootTest
class MilestoneDbTest {

    @Autowired
    private PmMilestoneService milestoneService;
    @Autowired
    private JdbcTemplate jdbc;

    private String activeProjectId;
    private String draftProjectId;
    private String phaseId;
    private String pkgId;
    private String taskId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("tms-user-pm", "tmspm", "TMS 项目经理");
        activeProjectId = seedProject("TMS 活跃项目", "ACTIVE", "tms-user-pm");
        draftProjectId = seedProject("TMS 草稿项目", "DRAFT", "tms-user-pm");
        phaseId = seedWbs(activeProjectId, 1, "TMS 阶段一", null);
        pkgId = seedWbs(activeProjectId, 2, "TMS 工作包", phaseId);
        taskId = seedWbs(activeProjectId, 3, "TMS 任务", pkgId);
        authAs("tms-junit", "PMO");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void createOnActiveProjectAndMountPhase() {
        // 同阶段连续创建两个里程碑 → 均 PLANNED
        Map<String, Object> m1 = milestoneService.create(Map.of(
                "projectId", activeProjectId, "wbsId", phaseId,
                "name", "TMS 需求评审", "planEndDate", LocalDate.now().plusDays(30).toString()));
        Map<String, Object> m2 = milestoneService.create(Map.of(
                "projectId", activeProjectId, "wbsId", phaseId,
                "name", "TMS 设计评审", "planEndDate", LocalDate.now().plusDays(60).toString()));
        assertEquals("PLANNED", m1.get("status"));
        assertEquals("PLANNED", m2.get("status"));
        // 非 ACTIVE 项目 → 422
        ServiceException e = assertThrows(ServiceException.class, () ->
                milestoneService.create(Map.of(
                        "projectId", draftProjectId, "wbsId", phaseId,
                        "name", "TMS 不该成功", "planEndDate", "2026-12-01")));
        assertEquals(422, e.getCode());
    }

    @Test
    void mountNonPhaseRejected() {
        // 工作包/任务级节点 → 422
        ServiceException e1 = assertThrows(ServiceException.class, () ->
                milestoneService.create(Map.of(
                        "projectId", activeProjectId, "wbsId", pkgId,
                        "name", "TMS 错挂包", "planEndDate", "2026-12-01")));
        assertTrue(e1.getMessage().contains("阶段"), e1.getMessage());
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                milestoneService.create(Map.of(
                        "projectId", activeProjectId, "wbsId", taskId,
                        "name", "TMS 错挂任务", "planEndDate", "2026-12-01")));
        assertEquals(422, e2.getCode());
    }

    @Test
    void dependencyChainAndCycle() {
        Map<String, Object> a = milestoneService.create(Map.of(
                "projectId", activeProjectId, "wbsId", phaseId,
                "name", "TMS 前序A", "planEndDate", LocalDate.now().plusDays(10).toString()));
        Map<String, Object> b = milestoneService.create(Map.of(
                "projectId", activeProjectId, "wbsId", phaseId,
                "name", "TMS 后序B", "planEndDate", LocalDate.now().plusDays(20).toString(),
                "prevMilestoneId", a.get("id")));
        assertEquals(a.get("id"), b.get("prevMilestoneId"));
        // 成环：A 的前序设为 B → 422
        ServiceException e = assertThrows(ServiceException.class, () ->
                milestoneService.update(String.valueOf(a.get("id")), Map.of(
                        "prevMilestoneId", b.get("id"))));
        assertTrue(e.getMessage().contains("环"), e.getMessage());
        // 自指 → 422
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                milestoneService.update(String.valueOf(a.get("id")), Map.of(
                        "prevMilestoneId", a.get("id"))));
        assertEquals(422, e2.getCode());
        // 空前序放行（清除依赖）
        Map<String, Object> cleared = milestoneService.update(String.valueOf(b.get("id")),
                Map.of("prevMilestoneId", ""));
        assertEquals(null, cleared.get("prevMilestoneId"));
    }

    @Test
    void deleteGates() {
        Map<String, Object> m = milestoneService.create(Map.of(
                "projectId", activeProjectId, "wbsId", phaseId,
                "name", "TMS 待删", "planEndDate", LocalDate.now().plusDays(10).toString()));
        String id = String.valueOf(m.get("id"));
        // PLANNED 无附件无评审 → 可删
        milestoneService.delete(id);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_milestone WHERE ID=? AND DEL_FLAG='1'",
                Integer.class, id), "逻辑删生效");
        // 有附件 → 不可删
        Map<String, Object> m2 = milestoneService.create(Map.of(
                "projectId", activeProjectId, "wbsId", phaseId,
                "name", "TMS 有附件", "planEndDate", LocalDate.now().plusDays(10).toString()));
        String id2 = String.valueOf(m2.get("id"));
        jdbc.update("INSERT INTO erp_sys_attachment (ID, BIZ_TYPE, BIZ_ID, FILE_NAME, STORE_PATH, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, 'MILESTONE_DELIV', ?, 'a.pdf', "
                + "'/tmp/a.pdf', 'junit', NOW(), '0', 0)", "tms-att-1", id2);
        ServiceException e = assertThrows(ServiceException.class, () -> milestoneService.delete(id2));
        assertTrue(e.getMessage().contains("交付物"), e.getMessage());
        // 被依赖 → 不可删
        Map<String, Object> m3 = milestoneService.create(Map.of(
                "projectId", activeProjectId, "wbsId", phaseId,
                "name", "TMS 被依赖", "planEndDate", LocalDate.now().plusDays(10).toString()));
        Map<String, Object> dep = milestoneService.create(Map.of(
                "projectId", activeProjectId, "wbsId", phaseId,
                "name", "TMS 依赖者", "planEndDate", LocalDate.now().plusDays(20).toString(),
                "prevMilestoneId", m3.get("id")));
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                milestoneService.delete(String.valueOf(m3.get("id"))));
        assertTrue(e2.getMessage().contains("前序"), e2.getMessage());
        assertNotNullOrNull(dep);
    }

    private static void assertNotNullOrNull(Object o) {
        assertTrue(o != null);
    }

    // ---------- helpers ----------

    private String seedProject(String name, String status, String pmUserId) {
        String id = "tms-prj-" + name.hashCode() % 1000;
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, '研发', 'INTERNAL', 'NS-TMS', ?, '2026-10-01', '2027-06-30', ?, '0', 0, "
                + "0.10, '0', 'junit', NOW(), '0', 0)",
                id, name, pmUserId, status);
        return id;
    }

    private String seedWbs(String projectId, int level, String name, String parentId) {
        String id = "tms-wbs-" + level + "-" + (name.hashCode() % 1000);
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, PARENT_ID, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, ?, ?, "
                + "'junit', NOW(), '0', 0)", id, projectId, "W" + level + id.substring(8, 12),
                parentId, level, name);
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
        jdbc.update("DELETE FROM erp_pm_ms_review WHERE MILESTONE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tms-prj-%') t)");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE='PmMilestone' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tms-prj-%') t)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmMilestone' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tms-prj-%') t)");
        jdbc.update("DELETE FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tms-prj-%'");
        jdbc.update("DELETE FROM erp_sys_attachment WHERE ID LIKE 'tms-att-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'tms-prj-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'tms-prj-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='tms-user-pm'");
    }
}
