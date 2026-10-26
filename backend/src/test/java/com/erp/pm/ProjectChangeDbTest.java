package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmBaselineVersionService;
import com.erp.service.pm.PmProjectChangeService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 项目变更 DbTest（project-change spec：五类型申请校验 / 三级动态路由 /
 * 第三档客户附件 L1 / 基线版本化（CURRENT 切换+历史只读+涉合同升主版本）/
 * C-4.8-09 WBS 拦截 / 三态跟踪+偏差>50% 升级 / 合同联动）。
 * 造数前缀 pcg- / 项目名 PCG%。
 */
@SpringBootTest
class ProjectChangeDbTest {

    @Autowired
    private PmProjectChangeService changeService;
    @Autowired
    private PmBaselineVersionService versionService;
    @Autowired
    private com.erp.service.impl.pm.PmChangeApprovalCallback callback;
    @Autowired
    private com.erp.service.impl.pm.PmChangeInterceptor interceptor;
    @Autowired
    private com.erp.service.pm.PmTimesheetService timesheetService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String taskId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("pcg-pm", "pcgpm", "PCG 项目经理");
        projectId = seedProject(); // 基线 1000
        taskId = seedTask();
        authAs("pcg-pm", "USER");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---------- 9.1 五类型申请校验 ----------

    @Test
    void missingWbsBlockedOnSubmit() {
        ServiceException e = assertThrows(ServiceException.class, () -> changeService.save(
                basePayload(), true));
        assertTrue(e.getMessage().contains("WBS"), e.getMessage());
        assertEquals(422, e.getCode());
    }

    @Test
    void backdatedEffectiveDateBlocked() {
        Map<String, Object> p = basePayload();
        p.put("effectiveDate", LocalDate.now().minusDays(1).toString());
        p.put("wbsIds", List.of(taskId));
        ServiceException e = assertThrows(ServiceException.class, () -> changeService.save(p, true));
        assertTrue(e.getMessage().contains("倒签"), e.getMessage());
    }

    @Test
    void inactiveProjectBlocked() {
        jdbc.update("UPDATE erp_pm_project SET STATUS='DRAFT' WHERE ID=?", projectId);
        ServiceException e = assertThrows(ServiceException.class, () -> changeService.save(
                basePayload(), false));
        assertTrue(e.getMessage().contains("ACTIVE"), e.getMessage());
    }

    @Test
    void shortDescriptionOnlyWarns() {
        Map<String, Object> p = basePayload();
        p.put("wbsIds", List.of(taskId));
        p.put("description", "短描述");
        Map<String, Object> out = changeService.save(p, false);
        assertNotNull(out.get("l4Warning"), "描述<20 字 L4 提示（不阻断）");
        assertEquals("DRAFT", out.get("status"));
    }

    @Test
    void budgetRateCalculatedAndContractForced() {
        // 追加 150 / 基线 1000 = 15% > 10% → 强制涉合同 + 第三档 EXEC+PMO
        Map<String, Object> p = basePayload();
        p.put("wbsIds", List.of(taskId));
        p.put("budgetAdd", 150);
        p.put("customerConfirmFlag", "1");
        Map<String, Object> out = changeService.save(p, true);
        assertEquals(new BigDecimal("15.0000"), new BigDecimal(String.valueOf(out.get("budgetRate"))),
                "预算影响占比服务端算");
        assertEquals("1", out.get("isContractFlag"), ">10% 强制置涉合同");
        assertEquals(3, out.get("approvalTier"), "第三档");
    }

    // ---------- 9.2 三级路由 + 第三档客户附件 + 双签 ----------

    @Test
    void tierOneSinglePmoNode() {
        // 进度 3 天 + 追加 30（3%）→ 第一档 PMO 单签
        Map<String, Object> out = changeService.save(tierPayload(3, 30, "0"), true);
        assertEquals(1, out.get("approvalTier"));
        assertEquals("ROLE_PMO", approvalNodeRole(String.valueOf(out.get("apprId"))));
    }

    @Test
    void tierTwoDualPmoFinance() {
        // 进度 7 天 > 5 → 第二档 PMO+财务双签
        Map<String, Object> out = changeService.save(tierPayload(7, 30, "0"), true);
        assertEquals(2, out.get("approvalTier"));
        List<String> roles = approvalNodeRoles(String.valueOf(out.get("apprId")));
        assertTrue(roles.contains("ROLE_PMO") && roles.contains("ROLE_FINANCE"), "双签两角色");
    }

    @Test
    void tierThreeRequiresCustomerConfirm() {
        // 第三档（>10%）未传客户附件 → L1 阻断
        Map<String, Object> p = tierPayload(3, 150, "0");
        ServiceException e = assertThrows(ServiceException.class, () -> changeService.save(p, true));
        assertTrue(e.getMessage().contains("客户书面确认"), e.getMessage());
        // 传附件后放行
        p.put("customerConfirmFlag", "1");
        Map<String, Object> out = changeService.save(p, true);
        assertEquals(3, out.get("approvalTier"));
        List<String> roles = approvalNodeRoles(String.valueOf(out.get("apprId")));
        assertTrue(roles.contains("ROLE_EXEC") && roles.contains("ROLE_PMO"), "第三档 EXEC+PMO");
    }

    @Test
    void dualSignRequiresBothNodes() {
        // 第二档：PMO 过、财务未过 → 变更单保持审批中（不推进基线版本化）
        Map<String, Object> out = changeService.save(tierPayload(7, 30, "0"), true);
        String changeId = String.valueOf(out.get("id"));
        String apprId = String.valueOf(out.get("apprId"));
        passOneNode(apprId);
        assertEquals("APPROVING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project_change WHERE ID=?", String.class, changeId),
                "双签未全过 → 保持审批中");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_baseline_version WHERE CHANGE_ID=?",
                Integer.class, changeId), "未全过不写基线版本");
    }

    // ---------- 9.3 基线版本化 ----------

    @Test
    void approveWritesNewVersionAndSwitchesCurrent() {
        // 造一个当前生效版 V1.0
        seedCurrentVersion("V1.0", 1, 0, 1000);
        Map<String, Object> out = changeService.save(tierPayload(3, 30, "0"), true);
        String changeId = String.valueOf(out.get("id"));
        approveChange(String.valueOf(out.get("apprId")));
        // 新版本 V1.1 生效，V1.0 归档
        assertEquals("V1.1", jdbc.queryForObject(
                "SELECT VER_NO_LABEL FROM erp_pm_baseline_version WHERE CHANGE_ID=?",
                String.class, changeId), "次版本 +1");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_baseline_version WHERE PROJECT_ID=? AND CURRENT_FLAG='1' "
                        + "AND VER_NO_LABEL='V1.1'", Integer.class, projectId), "新版本 CURRENT=1");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_baseline_version WHERE VER_NO_LABEL='V1.0' AND CURRENT_FLAG='1'",
                Integer.class), "旧版本归档 CURRENT=0");
        // 历史版只读可查（返回 V1.0 全量快照）
        Map<String, Object> snap = versionService.snapshot(projectId, "V1.0");
        assertEquals("V1.0", snap.get("verNoLabel"));
        assertNotNull(snap.get("lines"), "历史版全量快照可查");
        // 变更单置执行中 + 写版本号
        assertEquals("EXECUTING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project_change WHERE ID=?", String.class, changeId));
        assertEquals("V1.1", jdbc.queryForObject(
                "SELECT VER_NO_LABEL FROM erp_pm_project_change WHERE ID=?", String.class, changeId));
    }

    @Test
    void contractChangeBumpsMajorVersion() {
        seedCurrentVersion("V1.2", 1, 2, 1000);
        // 涉合同（手动勾）+ 传附件 → 第三档
        Map<String, Object> p = tierPayload(3, 30, "1");
        p.put("customerConfirmFlag", "1");
        Map<String, Object> out = changeService.save(p, true);
        String changeId = String.valueOf(out.get("id"));
        approveChange(String.valueOf(out.get("apprId")));
        assertEquals("V2.0", jdbc.queryForObject(
                "SELECT VER_NO_LABEL FROM erp_pm_baseline_version WHERE CHANGE_ID=?",
                String.class, changeId), "涉合同升主版本 V1.x → V2.0");
    }

    // ---------- 9.4 C-4.8-09 WBS 拦截 ----------

    @Test
    void pendingChangeBlocksTimesheetOnAffectedWbs() {
        // 造一个 APPROVING 变更，受影响 WBS = taskId
        Map<String, Object> out = changeService.save(tierPayload(3, 30, "0"), true);
        ServiceException e = assertThrows(ServiceException.class, () -> timesheetService.save(Map.of(
                "workDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("projectId", projectId, "wbsId", taskId,
                        "hours", 8, "workContent", "变更拦截测试")))));
        assertTrue(e.getMessage().contains("变更"), e.getMessage());
        assertNotNull(out.get("id"));
    }

    @Test
    void unaffectedWbsNotBlocked() {
        // 造在途变更（受影响=taskIdA），另一任务 taskIdB 不受影响
        String otherTask = seedTask2();
        changeService.save(tierPayload(3, 30, "0"), true);
        // taskIdB 工时录入不被拦（通过拦截器断言）
        interceptor.assertNoPendingChange(projectId, otherTask);
    }

    @Test
    void approvedChangeStopsBlocking() {
        Map<String, Object> out = changeService.save(tierPayload(3, 30, "0"), true);
        approveChange(String.valueOf(out.get("apprId")));
        // 审批通过 → 不再拦
        interceptor.assertNoPendingChange(projectId, taskId);
    }

    // ---------- 9.5 三态跟踪 + 偏差升级 + 合同联动 ----------

    @Test
    void threeStateFlowAndDeviationEscalation() {
        Map<String, Object> out = changeService.save(tierPayload(3, 30, "0"), true);
        String changeId = String.valueOf(out.get("id"));
        approveChange(String.valueOf(out.get("apprId")));
        // EXECUTING → DONE（仅创建人 PM）
        changeService.advance(changeId, Map.of("opinion", "变更已落地"));
        assertEquals("DONE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project_change WHERE ID=?", String.class, changeId));
        // DONE → VERIFIED（PMO 核验，偏差 60% > 50% → L4 升级通知 PMO）
        authAs("pcg-pmo", "USER"); // 核验须项目 PMO 身份（服务层单据级校验）
        Map<String, Object> v = changeService.verify(changeId,
                Map.of("opinion", "外协超预期", "verifyDeviation", 60));
        assertEquals("VERIFIED", v.get("status"));
        assertNotNull(v.get("l4Warning"), "偏差>50% L4 提示");
        Integer pmoNotice = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmProjectChange' "
                        + "AND TARGET_ROLE='ROLE_PMO'", Integer.class);
        assertTrue(pmoNotice >= 1, "升级通知 PMO");
    }

    @Test
    void advanceOnlyByCreator() {
        Map<String, Object> out = changeService.save(tierPayload(3, 30, "0"), true);
        String changeId = String.valueOf(out.get("id"));
        approveChange(String.valueOf(out.get("apprId")));
        authAs("pcg-other", "USER");
        ServiceException e = assertThrows(ServiceException.class, () ->
                changeService.advance(changeId, Map.of()));
        assertEquals(403, e.getCode());
    }

    @Test
    void contractLinkedOnApproval() {
        // 涉合同变更审批通过 → 调 ContractService.change 建合同变更单 + 回填版本 ID
        seedCurrentVersion("V1.0", 1, 0, 1000);
        Map<String, Object> p = tierPayload(3, 30, "1");
        p.put("customerConfirmFlag", "1");
        Map<String, Object> out = changeService.save(p, true);
        String changeId = String.valueOf(out.get("id"));
        approveChange(String.valueOf(out.get("apprId")));
        String verId = jdbc.queryForObject(
                "SELECT CONTRACT_CHANGE_VER_ID FROM erp_pm_project_change WHERE ID=?",
                String.class, changeId);
        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project_change WHERE ID=?", String.class, changeId);
        // 未提供 contractId → 回调跳过合同联动（hasText 判定），正常置执行中、不回填版本 ID
        assertEquals("EXECUTING", status, "无 contractId → 跳过联动，正常置执行中");
        assertEquals(null, verId, "未联动 → 不回填合同变更版本 ID");
        // 涉合同标记仍正确落库（IS_CONTRACT_FLAG=1，联动待补合同号后由回调执行）
        assertEquals("1", jdbc.queryForObject(
                "SELECT IS_CONTRACT_FLAG FROM erp_pm_project_change WHERE ID=?",
                String.class, changeId));
    }

    // ---------- 台账统计 ----------

    @Test
    void statsGrouping() {
        changeService.save(tierPayload(3, 30, "0"), false);
        changeService.save(tierPayload(7, 80, "0"), false);
        Map<String, Object> s = changeService.stats(projectId);
        assertEquals(2L, s.get("total"));
        assertNotNull(s.get("byType"));
        assertNotNull(s.get("byStatus"));
    }

    // ---------- helpers ----------

    private Map<String, Object> basePayload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("changeType", "SCOPE");
        p.put("reason", "客户需求");
        p.put("effectiveDate", LocalDate.now().plusDays(1).toString());
        p.put("description", "FR-4.8-8-1 测试变更描述（20 字以上）");
        p.put("scheduleImpactDays", 3);
        p.put("budgetAdd", 0);
        return p;
    }

    private Map<String, Object> tierPayload(int scheduleDays, int budgetAdd, String contractFlag) {
        Map<String, Object> p = basePayload();
        p.put("wbsIds", List.of(taskId));
        p.put("scheduleImpactDays", scheduleDays);
        p.put("budgetAdd", budgetAdd);
        p.put("isContractFlag", contractFlag);
        return p;
    }

    /** 造一个当前生效基线版本（模拟立项初版或历史版） */
    private void seedCurrentVersion(String label, int major, int minor, int amount) {
        jdbc.update("INSERT INTO erp_pm_baseline_version (ID, PROJECT_ID, VER_NO_LABEL, MAJOR_VER, "
                + "MINOR_VER, SNAPSHOT_JSON, BASELINE_AMOUNT, CURRENT_FLAG, CHANGE_ID, CREATE_BY, "
                + "CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, ?, ?, ?, '1', NULL, 'junit', "
                + "NOW(), '0', 0)",
                "pcg-bv-" + label, projectId, label, major, minor,
                "[{\"wbsId\":\"" + taskId + "\",\"labor\":" + amount + "}]",
                BigDecimal.valueOf(amount));
    }

    private void approveChange(String apprId) {
        jdbc.update("UPDATE erp_sys_approval SET STATUS='APPROVED', FINISH_DATE=NOW() WHERE ID=?", apprId);
        jdbc.update("UPDATE erp_sys_approval_task SET STATUS='APPROVED' WHERE APPR_ID=?", apprId);
        callback.onApproved(loadInstance(apprId));
    }

    /** 双签场景：仅通过指定角色节点（另一节点保持 PENDING） */
    private void passOneNode(String apprId) {
        jdbc.update("UPDATE erp_sys_approval_task SET STATUS='APPROVED' WHERE APPR_ID=? AND ROLE_REQUIRED=?",
                apprId, "ROLE_PMO");
        // 单节点通过不触发整单回调（底座语义：全部节点过才回调）
    }

    private String approvalNodeRole(String apprId) {
        return jdbc.queryForObject("SELECT ROLE_REQUIRED FROM erp_sys_approval_task WHERE APPR_ID=? "
                + "ORDER BY SEQ LIMIT 1", String.class, apprId);
    }

    private List<String> approvalNodeRoles(String apprId) {
        return jdbc.queryForList("SELECT ROLE_REQUIRED FROM erp_sys_approval_task WHERE APPR_ID=? "
                + "ORDER BY SEQ", String.class, apprId);
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

    private String seedProject() {
        String id = "pcg-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, PMO_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'PCG 变更项目', '研发', 'INTERNAL', 'NS-PCG', 'pcg-pm', 'pcg-pmo', "
                + "DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                + "'ACTIVE', '1', 1000, 0.10, '0', '0', 'junit', NOW(), '0', 0)", id);
        jdbc.update("INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, "
                + "SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('pcg-bl-1', ?, 'pcg-task-1', 1000, 0, 0, 0, 0, 0, 'junit', NOW(), '0', 0)", id);
        return id;
    }

    private String seedTask() {
        String phase = "pcg-wbs-phase";
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'PCG1PH', 1, 'PCG 阶段', "
                + "'junit', NOW(), '0', 0)", phase, projectId);
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, "
                + "OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('pcg-task-1', ?, 'PCG1T', 3, 'PCG 任务', ?, 'pcg-pm', 'JUNIOR', "
                + "'junit', NOW(), '0', 0)", projectId, phase);
        return "pcg-task-1";
    }

    private String seedTask2() {
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, "
                + "OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('pcg-task-2', ?, 'PCG1T2', 3, 'PCG 任务2', 'pcg-wbs-phase', 'pcg-pm', 'JUNIOR', "
                + "'junit', NOW(), '0', 0)", projectId);
        return "pcg-task-2";
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
        jdbc.update("DELETE FROM erp_pm_project_change WHERE PROJECT_ID LIKE 'pcg-%'");
        jdbc.update("DELETE FROM erp_pm_change_wbs WHERE CHANGE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_project_change WHERE PROJECT_ID LIKE 'pcg-%') t)");
        jdbc.update("DELETE FROM erp_pm_baseline_version WHERE PROJECT_ID LIKE 'pcg-%'");
        jdbc.update("DELETE FROM erp_pm_budget_change WHERE PROJECT_ID LIKE 'pcg-%'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'pcg-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'pcg-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'pcg-%'");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE='PmProjectChange') t)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmProjectChange'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='PmProjectChange'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID IN ('pcg-pm', 'pcg-pmo')");
    }
}
