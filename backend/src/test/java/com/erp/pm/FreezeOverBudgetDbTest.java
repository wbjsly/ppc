package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmCostVarianceService;
import com.erp.service.pm.PmOverBudgetService;
import com.erp.service.pm.PmTimesheetService;
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
 * 冻结与超预算审批 DbTest（cost-collection-freeze spec：双阈值口径 / 三类拦截 /
 * BR-4.8-22 三级动态路由 / 通过解冻+基线更新留痕 / 驳回保持冻结）。
 * 造数前缀 frz- / 项目名 FRZ%。
 */
@SpringBootTest
class FreezeOverBudgetDbTest {

    @Autowired
    private PmCostVarianceService varianceService;
    @Autowired
    private PmOverBudgetService overBudgetService;
    @Autowired
    private PmTimesheetService timesheetService;
    @Autowired
    private com.erp.service.impl.pm.PmOverBudgetApprovalCallback callback;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String taskId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("frz-emp", "frzemp", "FRZ 员工");
        projectId = seedProject(); // 基线 1000，窗口覆盖今天 50%
        taskId = seedTask();
        authAs("frz-emp", "USER");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---------- 双阈值口径（BR-4.8-21） ----------

    @Test
    void deviationOverToleranceOnlyAlerts() {
        // 基线 1000，50% 进度 → PV=500；实际 600 → 偏差 20% > 0.5% → 仅预警不冻结
        seedCollect(600);
        Map<String, Object> scan = varianceService.dailyScan();
        assertEquals(0, scan.get("frozen"), "偏差超容差但未超基线 → 不冻结");
        assertEquals(1, Integer.parseInt(String.valueOf(scan.get("red")))
                        + Integer.parseInt(String.valueOf(scan.get("yellow"))),
                "生成一条预警");
        assertEquals("0", freezeFlag(), "项目未冻结");
        Integer alerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_alert WHERE PROJECT_ID=?", Integer.class, projectId);
        assertEquals(1, alerts);
    }

    @Test
    void actualOverBaselineFreezes() {
        // 实际 1100 > 基线 1000 → 冻结
        seedCollect(1100);
        Map<String, Object> scan = varianceService.dailyScan();
        assertEquals(1, scan.get("frozen"), "超基线自动冻结");
        assertEquals("1", freezeFlag());
        assertNotNull(jdbc.queryForObject("SELECT FROZEN_AT FROM erp_pm_project WHERE ID=?",
                java.time.LocalDateTime.class, projectId));
        // 已冻结再扫 → skip 防重复通知
        Map<String, Object> again = varianceService.dailyScan();
        assertEquals(1, again.get("skipped"), "已冻结项目跳过");
    }

    @Test
    void toleranceIsAdjustable() {
        // 偏差 20%：COST_TOLERANCE=0.5 时预警；调到 30 后不预警
        seedCollect(600);
        varianceService.dailyScan();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_alert WHERE PROJECT_ID=?", Integer.class, projectId));
        jdbc.update("UPDATE erp_pm_cost_alert SET ALERT_DATE='2000-01-01'"); // 清同日幂等占位
        jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE='30' WHERE PARAM_KEY='COST_TOLERANCE'");
        jdbc.update("UPDATE erp_pm_cost_collect SET AMOUNT=460 WHERE PROJECT_ID=?", projectId);
        Map<String, Object> scan = varianceService.dailyScan();
        // 实际 460 vs PV 500 → 偏差 -8%（负偏差不超阈值）→ 无新预警
        assertEquals(0, scan.get("yellow"));
        assertEquals(0, scan.get("red"));
        jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE='0.5' WHERE PARAM_KEY='COST_TOLERANCE'");
    }

    // ---------- 三类拦截（BR-4.8-21 冻结范围） ----------

    @Test
    void frozenProjectBlocksTimesheetEntry() {
        jdbc.update("UPDATE erp_pm_project SET FREEZE_FLAG='1', FREEZE_REASON='FRZ 测试' WHERE ID=?", projectId);
        ServiceException e = assertThrows(ServiceException.class, () -> timesheetService.save(Map.of(
                "workDate", java.time.LocalDate.now().toString(),
                "lines", List.of(Map.of("projectId", projectId, "wbsId", taskId,
                        "hours", 8, "workContent", "冻结拦截测试")))));
        assertTrue(e.getMessage().contains("冻结"), e.getMessage());
    }

    // ---------- BR-4.8-22 三级动态路由 ----------

    @Test
    void tieredRoutingThreeLevels() {
        // 超支 3% → ROLE_PMO
        routeCase(1030, "ROLE_PMO");
        purgeProjectData();
        // 超支 8% → ROLE_FINANCE
        routeCase(1080, "ROLE_FINANCE");
        purgeProjectData();
        // 超支 12% → ROLE_EXEC（分管领导）
        routeCase(1120, "ROLE_EXEC");
    }

    private void routeCase(int actual, String expectRole) {
        seedCollect(actual);
        varianceService.dailyScan();
        assertEquals("1", freezeFlag(), "先冻结再申请");
        authAs("frz-emp", "USER");
        Map<String, Object> out = overBudgetService.submit(Map.of(
                "projectId", projectId, "reason", "FRZ 测试超支原因"));
        assertEquals(expectRole, out.get("approvalRole"), "超支比例路由");
        String role = jdbc.queryForObject(
                "SELECT APPROVAL_ROLE FROM erp_pm_over_budget WHERE ID=?",
                String.class, out.get("id"));
        assertEquals(expectRole, role);
    }

    @Test
    void applyOnUnfrozenProjectRejected() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                overBudgetService.submit(Map.of("projectId", projectId, "reason", "无冻结")));
        assertTrue(e.getMessage().contains("未冻结"), e.getMessage());
    }

    @Test
    void pendingDuplicateBlocked() {
        seedCollect(1030);
        varianceService.dailyScan();
        overBudgetService.submit(Map.of("projectId", projectId, "reason", "首次申请"));
        ServiceException e = assertThrows(ServiceException.class, () ->
                overBudgetService.submit(Map.of("projectId", projectId, "reason", "重复申请")));
        assertEquals(409, e.getCode());
    }

    // ---------- 通过解冻 + 基线更新留痕 / 驳回保持冻结 ----------

    @Test
    void approveUnfreezesAndUpdatesBaseline() {
        seedCollect(1080); // 超 8% → FINANCE 路由
        varianceService.dailyScan();
        Map<String, Object> out = overBudgetService.submit(Map.of(
                "projectId", projectId, "reason", "需求变更导致外协增加",
                "extraBudget", 500));
        assertEquals("ROLE_FINANCE", out.get("approvalRole"));
        String applyId = String.valueOf(out.get("id"));
        String apprId = jdbc.queryForObject(
                "SELECT APPR_ID FROM erp_pm_over_budget WHERE ID=?", String.class, applyId);
        // 底座通过 → 回调：解冻 + 追加 500 计入基线 + budget_change 留痕
        passApproval(apprId);
        assertEquals("0", freezeFlag(), "审批通过解冻");
        BigDecimal baseline = jdbc.queryForObject(
                "SELECT BASELINE_AMOUNT FROM erp_pm_project WHERE ID=?", BigDecimal.class, projectId);
        assertEquals(0, baseline.compareTo(new BigDecimal("1500.00")),
                "基线 1000 + 追加 500 = 1500，实际=" + baseline);
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_over_budget WHERE ID=?", String.class, applyId));
        // 预算变更留痕（CHANGE_NO=OBB+申请单号，申请单号含当天日期 → 按前缀查避免硬编码日期跨天失效）
        Integer trace = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_budget_change WHERE PROJECT_ID=? AND CHANGE_NO LIKE 'OBB%'",
                Integer.class, projectId);
        assertTrue(trace >= 1, "追加预算写 budget_change 留痕（CHANGE_NO=OBB+申请单号）");
    }

    @Test
    void rejectKeepsFrozen() {
        seedCollect(1030);
        varianceService.dailyScan();
        Map<String, Object> out = overBudgetService.submit(Map.of(
                "projectId", projectId, "reason", "申请被驳回场景"));
        String applyId = String.valueOf(out.get("id"));
        String apprId = jdbc.queryForObject(
                "SELECT APPR_ID FROM erp_pm_over_budget WHERE ID=?", String.class, applyId);
        rejectApproval(apprId, "节约措施不充分");
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_over_budget WHERE ID=?", String.class, applyId));
        assertEquals("1", freezeFlag(), "驳回后项目保持冻结");
        assertEquals("节约措施不充分", jdbc.queryForObject(
                "SELECT PM_OPINION FROM erp_pm_over_budget WHERE ID=?", String.class, applyId));
    }

    @Test
    void pureUnfreezeWithoutExtra() {
        seedCollect(1030);
        varianceService.dailyScan();
        Map<String, Object> out = overBudgetService.submit(Map.of(
                "projectId", projectId, "reason", "纯解冻不追加"));
        passApproval(jdbc.queryForObject("SELECT APPR_ID FROM erp_pm_over_budget WHERE ID=?",
                String.class, out.get("id")));
        assertEquals("0", freezeFlag(), "纯解冻");
        BigDecimal baseline = jdbc.queryForObject(
                "SELECT BASELINE_AMOUNT FROM erp_pm_project WHERE ID=?", BigDecimal.class, projectId);
        assertEquals(0, baseline.compareTo(new BigDecimal("1000.00")), "无追加基线不变");
    }

    // ---------- helpers ----------

    private void passApproval(String apprId) {
        jdbc.update("UPDATE erp_sys_approval SET STATUS='APPROVED', FINISH_DATE=NOW() WHERE ID=?", apprId);
        jdbc.update("UPDATE erp_sys_approval_task SET STATUS='APPROVED' WHERE APPR_ID=?", apprId);
        callback.onApproved(loadInstance(apprId));
    }

    private void rejectApproval(String apprId, String opinion) {
        jdbc.update("UPDATE erp_sys_approval SET STATUS='REJECTED', FINISH_DATE=NOW() WHERE ID=?", apprId);
        jdbc.update("UPDATE erp_sys_approval_task SET STATUS='REJECTED', OPINION=? WHERE APPR_ID=?",
                opinion, apprId);
        callback.onRejected(loadInstance(apprId));
    }

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

    private String freezeFlag() {
        return jdbc.queryForObject("SELECT FREEZE_FLAG FROM erp_pm_project WHERE ID=?",
                String.class, projectId);
    }

    /** 实际累计成本（模拟已归集） */
    private void seedCollect(int amount) {
        jdbc.update("INSERT INTO erp_pm_cost_collect (ID, PROJECT_ID, COST_TYPE, AMOUNT, SOURCE_TYPE, "
                + "SOURCE_ID, COLLECT_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'MATERIAL', ?, 'MAT_ISSUE', ?, CURDATE(), 'junit', NOW(), '0', 0)",
                "frz-cc-1", projectId, BigDecimal.valueOf(amount), "frz-src-1");
    }

    /** 清项目级数据（保留项目与 WBS，供同测内多场景重跑） */
    private void purgeProjectData() {
        jdbc.update("DELETE FROM erp_pm_over_budget WHERE PROJECT_ID=?", projectId);
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='PmOverBudget') t)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmOverBudget'");
        jdbc.update("DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID=?", projectId);
        jdbc.update("DELETE FROM erp_pm_cost_alert WHERE PROJECT_ID=?", projectId);
        jdbc.update("UPDATE erp_pm_project SET FREEZE_FLAG='0', FREEZE_REASON=NULL, FROZEN_AT=NULL "
                + "WHERE ID=?", projectId);
    }

    private String seedProject() {
        String id = "frz-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'FRZ 冻结项目', '研发', 'INTERNAL', 'NS-FRZ', 'frz-emp', "
                + "DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 10 DAY), "
                + "'ACTIVE', '0', 1000, 0.10, '0', '0', 'junit', NOW(), '0', 0)", id);
        // 预算行（基线 1000 = LABOR 列）
        jdbc.update("INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, "
                + "SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('frz-bl-1', ?, 'frz-task-1', 1000, 0, 0, 0, 0, 0, 'junit', NOW(), '0', 0)", id);
        return id;
    }

    private String seedTask() {
        String phase = "frz-wbs-phase";
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'FRZ1PH', 1, 'FRZ 阶段', "
                + "'junit', NOW(), '0', 0)", phase, projectId);
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, "
                + "OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('frz-task-1', ?, 'FRZ1T', 3, 'FRZ 任务', ?, 'frz-emp', 'JUNIOR', "
                + "'junit', NOW(), '0', 0)", projectId, phase);
        return "frz-task-1";
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
        jdbc.update("DELETE FROM erp_pm_over_budget WHERE PROJECT_ID LIKE 'frz-%'");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='PmOverBudget') t)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmOverBudget'");
        jdbc.update("DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID LIKE 'frz-%'");
        jdbc.update("DELETE FROM erp_pm_cost_alert WHERE PROJECT_ID LIKE 'frz-%'");
        jdbc.update("DELETE FROM erp_pm_budget_change WHERE PROJECT_ID LIKE 'frz-%'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'frz-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'frz-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'frz-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='frz-emp'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmCost%'");
    }
}
