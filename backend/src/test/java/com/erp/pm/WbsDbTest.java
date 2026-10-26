package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.entity.pm.PmProject;
import com.erp.service.pm.PmProjectService;
import com.erp.service.pm.PmTemplateService;
import com.erp.service.pm.PmWbsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * WBS DbTest（project-initiation spec：三层分解/任务级校验/模板生成/预算行级联）。
 * 造数前缀 twb- / 项目名 TWB%。
 */
@SpringBootTest
class WbsDbTest {

    @Autowired
    private PmWbsService wbsService;
    @Autowired
    private PmProjectService projectService;
    @Autowired
    private PmTemplateService templateService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("twb-junit", "TPI 测试 PMO");
        authAs("twb-junit", "ROLE_PMO");
        PmProject p = projectService.create(Map.of("name", "TWB 测试项目", "projType", "研发",
                "deliveryPath", "INTERNAL", "internalFormNo", "IF-TWB",
                "pmUserId", "twb-junit", "startDate", "2026-11-01", "endDate", "2027-05-31"));
        projectId = p.getId();
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
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='twb-junit'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TWB%')");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TWB%')");
        jdbc.update("DELETE FROM erp_pm_project WHERE NAME LIKE 'TWB%'");
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(roles)));
    }

    /** 建一套完整三层：阶段→工作包→任务，返回任务 ID */
    private String seedFullTree() {
        Map<String, Object> phase = wbsService.create(Map.of(
                "projectId", projectId, "level", 1, "name", "需求阶段"));
        Map<String, Object> pkg = wbsService.create(Map.of(
                "projectId", projectId, "level", 2, "parentId", phase.get("id"),
                "name", "需求分析", "deliverable", "需求规格说明书"));
        Map<String, Object> task = wbsService.create(Map.of(
                "projectId", projectId, "level", 3, "parentId", pkg.get("id"),
                "name", "调研任务", "ownerUserId", "twb-owner", "ownerGrade", "SENIOR",
                "planStart", "2026-11-02", "planEnd", "2026-11-13", "estHours", "100"));
        return String.valueOf(task.get("id"));
    }

    @Test
    void hierarchyRulesEnforced() {
        // level1 带父 → 422
        Map<String, Object> phase = wbsService.create(Map.of(
                "projectId", projectId, "level", 1, "name", "主阶段"));
        ServiceException e1 = assertThrows(ServiceException.class, () -> wbsService.create(Map.of(
                "projectId", projectId, "level", 1, "name", "违规阶段", "parentId", phase.get("id"))));
        assertEquals(422, e1.getCode());
        // 工作包无交付物 → 422
        ServiceException e2 = assertThrows(ServiceException.class, () -> wbsService.create(Map.of(
                "projectId", projectId, "level", 2, "parentId", phase.get("id"), "name", "无交付物包")));
        assertEquals(422, e2.getMessage().contains("交付物") ? 422 : 0);
        // 任务直接挂阶段 → 422
        ServiceException e3 = assertThrows(ServiceException.class, () -> wbsService.create(Map.of(
                "projectId", projectId, "level", 3, "parentId", phase.get("id"), "name", "越级任务")));
        assertEquals(422, e3.getCode());
        // 层级不可改
        Map<String, Object> pkg = wbsService.create(Map.of(
                "projectId", projectId, "level", 2, "parentId", phase.get("id"),
                "name", "正常包", "deliverable", "D1"));
        ServiceException e4 = assertThrows(ServiceException.class,
                () -> wbsService.update(String.valueOf(pkg.get("id")), Map.of("level", 3)));
        assertEquals(422, e4.getCode());
        // 父节点不可改
        ServiceException e5 = assertThrows(ServiceException.class,
                () -> wbsService.update(String.valueOf(pkg.get("id")), Map.of("parentId", "other")));
        assertEquals(422, e5.getCode());
    }

    @Test
    void validateForBaselineListsMissingTasks() {
        // 空 WBS
        List<String> empty = wbsService.validateForBaseline(projectId);
        assertFalse(empty.isEmpty());
        assertTrue(empty.get(0).contains("WBS 为空"));
        // 阶段下无工作包
        Map<String, Object> phase = wbsService.create(Map.of(
                "projectId", projectId, "level", 1, "name", "孤阶段"));
        List<String> noPkg = wbsService.validateForBaseline(projectId);
        assertTrue(noPkg.stream().anyMatch(s -> s.contains("孤阶段") && s.contains("工作包")), noPkg.toString());
        // 工作包下无任务
        wbsService.create(Map.of("projectId", projectId, "level", 2, "parentId", phase.get("id"),
                "name", "孤包", "deliverable", "D"));
        List<String> noTask = wbsService.validateForBaseline(projectId);
        assertTrue(noTask.stream().anyMatch(s -> s.contains("孤包") && s.contains("任务")), noTask.toString());
        // 清掉不完整的孤分支（级联删孤包），再补完整树 → 通过
        wbsService.delete(String.valueOf(phase.get("id")));
        seedFullTree();
        assertTrue(wbsService.validateForBaseline(projectId).isEmpty());
    }

    @Test
    void taskCascadesBudgetLineOnCreateAndDelete() {
        String taskId = seedFullTree();
        Integer lineCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_budget_line WHERE WBS_ID=? AND DEL_FLAG='0'",
                Integer.class, taskId);
        assertEquals(1, lineCount, "任务创建级联建空预算行");
        // 删工作包 → 任务与预算行级联删
        String pkgId = jdbc.queryForObject(
                "SELECT PARENT_ID FROM erp_pm_wbs WHERE ID=?", String.class, taskId);
        wbsService.delete(pkgId);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_wbs WHERE ID=? AND DEL_FLAG='0'", Integer.class, taskId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_budget_line WHERE WBS_ID=? AND DEL_FLAG='0'",
                Integer.class, taskId), "预算行级联删");
    }

    @Test
    void templateGenerateSkeletonAndConfirmOverwrite() {
        String tplId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_wbs_template WHERE TEMPLATE_NO='TPL-RD' AND STATUS='1'",
                String.class);
        assertNotNull(tplId, "研发模板种子存在");
        // 一键生成
        Map<String, Object> out = templateService.generate(projectId, tplId, false);
        assertNotNull(out);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_wbs WHERE PROJECT_ID=? AND DEL_FLAG='0'", Integer.class, projectId);
        assertTrue(total >= 5, "模板骨架已生成（" + total + "）");
        Integer tasks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_wbs WHERE PROJECT_ID=? AND LEVEL=3 AND DEL_FLAG='0'",
                Integer.class, projectId);
        assertTrue(tasks >= 2, "含任务级节点");
        // 重复生成须 confirm
        ServiceException e = assertThrows(ServiceException.class,
                () -> templateService.generate(projectId, tplId, false));
        assertEquals(422, e.getCode());
        // confirm=true 整体覆盖
        templateService.generate(projectId, tplId, true);
        assertEquals(total, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_wbs WHERE PROJECT_ID=? AND DEL_FLAG='0'", Integer.class, projectId),
                "覆盖生成数量一致");
        // 停用模板不可选
        jdbc.update("UPDATE erp_pm_wbs_template SET STATUS='0' WHERE ID=?", tplId);
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> templateService.generate(projectId, tplId, true));
        assertEquals(422, e2.getCode());
        jdbc.update("UPDATE erp_pm_wbs_template SET STATUS='1' WHERE ID=?", tplId);
    }
}
