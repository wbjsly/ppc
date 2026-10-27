package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.entity.pm.PmProject;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.pm.PmBudgetChangeService;
import com.erp.service.pm.PmBudgetService;
import com.erp.service.pm.PmProjectService;
import com.erp.service.pm.PmWbsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 预算与基线审批 DbTest（project-initiation spec：预算计算/零预算阻断/
 * 基线通过联动/驳回/锁定只读/变更双签）。
 * 造数前缀 tab- / 项目名 TAB%。
 */
@SpringBootTest
class BudgetApprovalDbTest {

    @Autowired
    private PmProjectService projectService;
    @Autowired
    private PmWbsService wbsService;
    @Autowired
    private PmBudgetService budgetService;
    @Autowired
    private PmBudgetChangeService changeService;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String taskId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("tab-junit", "TPI 预算 PMO");
        authAs("tab-junit", "ROLE_PMO");
        PmProject p = projectService.create(Map.of("name", "TAB 预算项目", "projType", "研发",
                "deliveryPath", "INTERNAL", "internalFormNo", "IF-TAB",
                "pmUserId", "tab-junit", "startDate", "2026-11-01", "endDate", "2027-05-31"));
        projectId = p.getId();
        // IP 归属协议卡控（BR-4.8-24，add-ip-management）：立项须有生效协议，直插一条独立研发协议
        jdbc.update("INSERT INTO erp_pm_ip_agreement (ID, PROJECT_ID, COOP_MODE, COMPANY_RATE, "
                + "SIGN_DATE, EFFECTIVE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('tab-ip-agr', ?, 'INDEP', 100, CURDATE(), '1', 'junit', NOW(), '0', 0)", projectId);
        Map<String, Object> phase = wbsService.create(Map.of(
                "projectId", projectId, "level", 1, "name", "开发阶段"));
        Map<String, Object> pkg = wbsService.create(Map.of(
                "projectId", projectId, "level", 2, "parentId", phase.get("id"),
                "name", "编码工作包", "deliverable", "代码与单元测试报告"));
        Map<String, Object> task = wbsService.create(Map.of(
                "projectId", projectId, "level", 3, "parentId", pkg.get("id"),
                "name", "核心模块开发", "ownerUserId", "tab-owner", "ownerGrade", "SENIOR",
                "planStart", "2026-11-02", "planEnd", "2026-12-31", "estHours", "100"));
        taskId = String.valueOf(task.get("id"));
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

    private     void purge() {
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='tab-junit'");
        jdbc.update("DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%')");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE IN ('PmBaseline','PmBudgetChange') "
                + "AND (i.BIZ_ID IN (SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%') "
                + " OR i.BIZ_ID IN (SELECT ID FROM erp_pm_budget_change WHERE PROJECT_ID "
                + "   IN (SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%')))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('PmBaseline','PmBudgetChange') "
                + "AND (BIZ_ID IN (SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%') "
                + " OR BIZ_ID IN (SELECT ID FROM erp_pm_budget_change WHERE PROJECT_ID "
                + "   IN (SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%')))");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%')");
        jdbc.update("DELETE FROM erp_pm_budget_change WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%')");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%')");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TAB%' OR NAME LIKE 'TAB0%')");
        jdbc.update("DELETE FROM erp_pm_project WHERE NAME LIKE 'TAB%'");
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(roles)));
    }

    private String activeTaskId(String bizId) {
        return jdbc.queryForObject("SELECT t.ID FROM erp_sys_approval_task t "
                        + "JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                        + "WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE' ORDER BY t.SEQ LIMIT 1",
                String.class, bizId);
    }

    @Test
    void laborAndIndirectAutoComputed() {
        // 材料 5000 + 人工 100h × SENIOR 160 = 16000 → 五项 21000 → 间接 2100
        budgetService.saveLines(projectId, Map.of("lines", List.of(
                Map.of("wbsId", taskId, "material", "5000"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) budgetService.lines(projectId).get("lines");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(0, new BigDecimal("16000").compareTo(toDec(row.get("labor"))), "人工=工时×费率");
        assertEquals(0, new BigDecimal("2100").compareTo(toDec(row.get("indirect"))), "间接=五项×10%");
        // 项目级费率覆盖：高级 200 → 人工 20000（update() 需全量字段，这里直改列）
        jdbc.update("UPDATE erp_pm_project SET RATE_OVERRIDE=? WHERE ID=?",
                "{\"SENIOR\":200}", projectId);
        budgetService.saveLines(projectId, Map.of("lines", List.of(
                Map.of("wbsId", taskId, "material", "5000"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows2 = (List<Map<String, Object>>) budgetService.lines(projectId).get("lines");
        assertEquals(0, new BigDecimal("20000").compareTo(
                toDec(rows2.get(0).get("labor"))), "项目费率覆盖优先");
    }

    @Test
    void zeroBudgetBlockedAndIncompleteWbsBlocked() {
        // 零预算：任务工时置 0（人工=0，无其他成本）→ 合计 0 → 422（FR-4.8-2-3）
        jdbc.update("UPDATE erp_pm_wbs SET EST_HOURS=0 WHERE ID=?", taskId);
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> budgetService.submitBaseline(projectId));
        assertEquals(422, e1.getCode());
        assertTrue(e1.getMessage().contains("0"), e1.getMessage());
        // WBS 未分解至任务级：删任务（级联预算行）→ 422 带节点清单（BR-4.8-10）
        jdbc.update("UPDATE erp_pm_wbs SET EST_HOURS=100 WHERE ID=?", taskId);
        wbsService.delete(taskId);
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> budgetService.submitBaseline(projectId));
        assertEquals(422, e2.getCode());
        assertTrue(e2.getMessage().contains("任务"), e2.getMessage());
    }

    @Test
    void baselineApproveActivatesAndNumbersProject() {
        budgetService.saveLines(projectId, Map.of("lines", List.of(
                Map.of("wbsId", taskId, "material", "5000"))));
        Map<String, Object> submitted = budgetService.submitBaseline(projectId);
        assertNotNull(submitted.get("apprId"));
        assertEquals("APPROVING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project WHERE ID=?", String.class, projectId));
        // PMO 通过
        authAs("tab-pmo", "ROLE_PMO");
        engine.pass(activeTaskId(projectId), "基线合理，同意启动");
        String status = jdbc.queryForObject("SELECT STATUS FROM erp_pm_project WHERE ID=?",
                String.class, projectId);
        assertEquals("ACTIVE", status, "通过后项目启动");
        String locked = jdbc.queryForObject("SELECT BASELINE_LOCKED FROM erp_pm_project WHERE ID=?",
                String.class, projectId);
        assertEquals("1", locked, "基线锁定");
        String no = jdbc.queryForObject("SELECT PROJECT_NO FROM erp_pm_project WHERE ID=?",
                String.class, projectId);
        assertTrue(no != null && no.matches("PRJ\\d{4}\\d{4}"), "编号 PRJ+年+流水：" + no);
        // 通知 PM 与 PMO
        Integer notice = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmBaseline' AND BIZ_ID=?",
                Integer.class, projectId);
        assertTrue(notice >= 2, "站内通知 PM+PMO：" + notice);
        // 锁定后预算只读
        ServiceException e = assertThrows(ServiceException.class,
                () -> budgetService.saveLines(projectId, Map.of("lines", List.of(
                        Map.of("wbsId", taskId, "material", "1")))));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("锁定"), e.getMessage());
        // 重复提交被拒
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> budgetService.submitBaseline(projectId));
        assertEquals(422, e2.getCode());
    }

    @Test
    void baselineRejectReturnsToDraft() {
        budgetService.saveLines(projectId, Map.of("lines", List.of(
                Map.of("wbsId", taskId, "material", "5000"))));
        budgetService.submitBaseline(projectId);
        authAs("tab-pmo", "ROLE_PMO");
        engine.reject(activeTaskId(projectId), "费用依据不足，请补充");
        assertEquals("DRAFT", jdbc.queryForObject("SELECT STATUS FROM erp_pm_project WHERE ID=?",
                String.class, projectId));
        String opinion = jdbc.queryForObject("SELECT REJECT_OPINION FROM erp_pm_project WHERE ID=?",
                String.class, projectId);
        assertTrue(opinion.contains("依据不足"), opinion);
        // 驳回后可改预算重提
        budgetService.saveLines(projectId, Map.of("lines", List.of(
                Map.of("wbsId", taskId, "material", "8000"))));
        budgetService.submitBaseline(projectId);
        assertEquals("APPROVING", jdbc.queryForObject("SELECT STATUS FROM erp_pm_project WHERE ID=?",
                String.class, projectId));
    }

    @Test
    void budgetChangeDualSignAndRejectKeepsBaseline() {
        // 先启动项目
        budgetService.saveLines(projectId, Map.of("lines", List.of(
                Map.of("wbsId", taskId, "material", "5000"))));
        budgetService.submitBaseline(projectId);
        authAs("tab-pmo", "ROLE_PMO");
        engine.pass(activeTaskId(projectId), "同意");
        BigDecimal before = jdbc.queryForObject(
                "SELECT BASELINE_AMOUNT FROM erp_pm_project WHERE ID=?", BigDecimal.class, projectId);
        // 变更申请：理由 + 新快照（材料 9000）
        Map<String, Object> change = changeService.submit(projectId, Map.of(
                "reason", "范围追加材料采购",
                "lines", List.of(Map.of("wbsId", taskId, "material", "9000"))));
        assertNotNull(change.get("apprId"));
        String changeId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_budget_change WHERE PROJECT_ID=? ORDER BY CREATE_DATE DESC LIMIT 1",
                String.class, projectId);
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_budget_change WHERE ID=?", String.class, changeId));
        // 双签：PMO → 财务
        authAs("tab-pmo", "ROLE_PMO");
        engine.pass(activeTaskId(changeId), "PMO 同意");
        authAs("tab-fin", "ROLE_FINANCE");
        engine.pass(activeTaskId(changeId), "财务同意");
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_budget_change WHERE ID=?", String.class, changeId));
        // 新基线生效：材料 9000 + 人工 16000 = 25000 + 间接 2500 = 27500
        BigDecimal after = jdbc.queryForObject(
                "SELECT BASELINE_AMOUNT FROM erp_pm_project WHERE ID=?", BigDecimal.class, projectId);
        assertEquals(0, new BigDecimal("27500").compareTo(after), "变更后基线：" + after);
        assertTrue(after.compareTo(before) > 0);
        // 驳回场景：再提一单 → 财务驳回 → 基线不变
        Map<String, Object> change2 = changeService.submit(projectId, Map.of(
                "reason", "二次变更尝试",
                "lines", List.of(Map.of("wbsId", taskId, "material", "99999"))));
        String changeId2 = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_budget_change WHERE APPR_ID=? AND ID<>?",
                String.class, String.valueOf(change2.get("apprId")), changeId);
        authAs("tab-pmo", "ROLE_PMO");
        engine.pass(activeTaskId(changeId2), "同意");
        authAs("tab-fin", "ROLE_FINANCE");
        engine.reject(activeTaskId(changeId2), "超出本年预算盘子");
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_budget_change WHERE ID=?", String.class, changeId2));
        assertEquals(0, new BigDecimal("27500").compareTo(jdbc.queryForObject(
                "SELECT BASELINE_AMOUNT FROM erp_pm_project WHERE ID=?", BigDecimal.class, projectId)),
                "驳回后基线不变");
        // PENDING 存在时不可重复申请
        Map<String, Object> change3 = changeService.submit(projectId, Map.of(
                "reason", "再来一单",
                "lines", List.of(Map.of("wbsId", taskId, "material", "1"))));
        // change2 已 REJECTED 不算 pending；change3 可提交，但立即再提会 422
        ServiceException e = assertThrows(ServiceException.class, () -> changeService.submit(projectId,
                Map.of("reason", "重复申请",
                        "lines", List.of(Map.of("wbsId", taskId, "material", "1")))));
        assertEquals(422, e.getCode());
        assertNotNull(change3.get("apprId"));
    }

    private static BigDecimal toDec(Object v) {
        return v instanceof BigDecimal d ? d : new BigDecimal(String.valueOf(v));
    }
}
