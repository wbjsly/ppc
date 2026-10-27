package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.entity.pm.ClosureCheck;
import com.erp.service.impl.pm.PmClosureServiceImpl;
import com.erp.service.pm.PmClosureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结项检查 DbTest ①组（add-project-closure，spec project-closure ADDED）：
 * 发起生成/证据数值/重算/人工项附件/强制项卡控/结算与报告前置/提交冻结/撤回。
 * 造数前缀 clc-。
 */
@SpringBootTest
class ClosureDbTest {

    @Autowired
    private PmClosureService service;
    @Autowired
    private PmClosureServiceImpl impl;
    @Autowired
    private com.erp.service.impl.pm.PmClosureApprovalCallback callback;
    @Autowired
    private com.erp.service.pm.PmClosureSettlementService settlement;
    @Autowired
    private com.erp.service.impl.pm.PmClosureGuard guard;
    @Autowired
    private com.erp.schedule.PmClosureDailyTask dailyTask;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String PRJ = "clc-prj-1";

    @BeforeEach
    void setUp() {
        purge();
        seedProject(PRJ, "ACTIVE");
        asRole("ROLE_PM");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    // ==================== 发起与证据 ====================

    @Test
    void initiateGeneratesCheckWithEvidence() {
        seedPhase("clc-ph-1", "1");
        seedPhase("clc-ph-2", "1");
        seedMsApproved();
        seedIpAgreement();
        seedCost("clc-ph-1", "1000");
        Map<String, Object> out = service.initiate(PRJ);
        assertEquals("CLOSING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project WHERE ID=?", String.class, PRJ));
        assertEquals(ClosureCheck.ST_DRAFT, jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_closure_check WHERE PROJECT_ID=?", String.class, PRJ));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals(6, items.size(), "固定六项");
        Map<String, Object> wbs = itemByKey(items, "WBS");
        assertEquals("PASS", wbs.get("result"));
        assertTrue(String.valueOf(wbs.get("evidenceJson")).contains("doneRate"),
                "数值证据非布尔：" + String.valueOf(wbs.get("evidenceJson")));
        assertEquals("PASS", itemByKey(items, "MS").get("result"));
        assertEquals("PASS", itemByKey(items, "COST").get("result"));
        assertEquals("PASS", itemByKey(items, "IP").get("result"));
        assertEquals("PENDING", itemByKey(items, "ACCEPT").get("result"), "人工项待确认");
    }

    @Test
    void initiateNonActiveRejected() {
        jdbc.update("UPDATE erp_pm_project SET STATUS='DRAFT' WHERE ID=?", PRJ);
        ServiceException e = assertThrows(ServiceException.class, () -> service.initiate(PRJ));
        assertEquals(422, e.getCode());
    }

    @Test
    void initiateDuplicateRejected() {
        service.initiate(PRJ);
        ServiceException e = assertThrows(ServiceException.class, () -> service.initiate(PRJ));
        assertEquals(409, e.getCode());
    }

    @Test
    void evidenceWbsIncompleteFails() {
        seedPhase("clc-ph-1", "1");
        seedPhase("clc-ph-2", "0"); // 未完成
        seedIpAgreement();
        Map<String, Object> out = service.initiate(PRJ);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        Map<String, Object> wbs = itemByKey(items, "WBS");
        assertEquals("FAIL", wbs.get("result"));
        assertTrue(String.valueOf(wbs.get("evidenceJson")).contains("50.0"),
                "完成度 50% 落证据：" + String.valueOf(wbs.get("evidenceJson")));
    }

    @Test
    void evidenceOpenMilestoneFails() {
        seedPhase("clc-ph-1", "1");
        jdbc.update("INSERT INTO erp_pm_milestone (ID, PROJECT_ID, WBS_ID, NAME, PLAN_END_DATE, "
                + "STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES ('clc-ms-1', ?, "
                + "'clc-ph-1', '联调里程碑', DATE_ADD(CURDATE(), INTERVAL 5 DAY), 'REVIEWING', "
                + "'junit', NOW(), '0', 0)", PRJ);
        seedIpAgreement();
        Map<String, Object> out = service.initiate(PRJ);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        Map<String, Object> ms = itemByKey(items, "MS");
        assertEquals("FAIL", ms.get("result"));
        assertTrue(String.valueOf(ms.get("evidenceJson")).contains("\"openMs\":1"),
                String.valueOf(ms.get("evidenceJson")));
    }

    @Test
    void evidenceCostDeviationFailsWhenUncollected() {
        seedPhase("clc-ph-1", "1");
        seedIpAgreement();
        // 基线 1000、归集 0 → 偏差 -100% FAIL
        Map<String, Object> out = service.initiate(PRJ);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals("FAIL", itemByKey(items, "COST").get("result"));
    }

    @Test
    void evidenceIpMissingFails() {
        seedPhase("clc-ph-1", "1");
        // 无 IP 协议
        Map<String, Object> out = service.initiate(PRJ);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        Map<String, Object> ip = itemByKey(items, "IP");
        assertEquals("FAIL", ip.get("result"));
        assertTrue(String.valueOf(ip.get("evidenceJson")).contains("\"missingIp\":1"));
    }

    @Test
    void resubmitRefreshesAutoKeepsManual() {
        seedPhase("clc-ph-1", "1");
        seedIpAgreement();
        service.initiate(PRJ);
        // 人工项先确认 PASS（带附件）
        String acceptId = itemIdOf(PRJ, "ACCEPT");
        seedAttach(acceptId);
        service.confirmItem(acceptId, Map.of("result", "PASS", "attachIds", "att-1"));
        // 阶段回退 → 重新校验应翻转 WBS，但 ACCEPT 保留 PASS
        jdbc.update("UPDATE erp_pm_wbs SET PHASE_DONE='0' WHERE ID='clc-ph-1'");
        Map<String, Object> out = service.resubmit(PRJ);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals("FAIL", itemByKey(items, "WBS").get("result"), "自动项刷新");
        assertEquals("PASS", itemByKey(items, "ACCEPT").get("result"), "人工项保留");
    }

    // ==================== 人工项与卡控 ====================

    @Test
    void confirmPassRequiresAttachment() {
        service.initiate(PRJ);
        String docId = itemIdOf(PRJ, "DOC");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.confirmItem(docId, Map.of("result", "PASS")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("附件"), e.getMessage());
    }

    @Test
    void confirmAutoItemRejected() {
        service.initiate(PRJ);
        String wbsId = itemIdOf(PRJ, "WBS");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.confirmItem(wbsId, Map.of("result", "PASS", "attachIds", "x")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("重新校验"), e.getMessage());
    }

    @Test
    void submitBlockedByMandatoryFail() {
        // 仅 WBS 阶段完成，COST/IP 失败
        seedPhase("clc-ph-1", "1");
        service.initiate(PRJ);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.submitForApproval(PRJ));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("强制检查项未通过"), e.getMessage());
    }

    @Test
    void submitBlockedWithoutSettlement() {
        makeAllMandatoryPass();
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.submitForApproval(PRJ));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("财务成本结算"), e.getMessage());
    }

    @Test
    void submitBlockedWithoutProjectReport() {
        makeAllMandatoryPass();
        seedSettlement(PRJ);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.submitForApproval(PRJ));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("项目总结报告"), e.getMessage());
    }

    @Test
    void submitFreezesAndWithdrawRestores() {
        makeAllMandatoryPass();
        seedSettlement(PRJ);
        seedAttach(PRJ); // 项目总结报告
        Map<String, Object> out = service.submitForApproval(PRJ);
        assertEquals(ClosureCheck.ST_FROZEN, jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_closure_check WHERE PROJECT_ID=?", String.class, PRJ));
        String approvalId = jdbc.queryForObject(
                "SELECT APPROVAL_ID FROM erp_pm_closure_check WHERE PROJECT_ID=?", String.class, PRJ);
        assertNotNull(approvalId, "审批实例已创建");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE ID=?", String.class, approvalId));
        // 撤回：项目退 ACTIVE，检查单解锁，审批单 CANCELLED
        service.withdraw(PRJ);
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project WHERE ID=?", String.class, PRJ));
        assertEquals(ClosureCheck.ST_DRAFT, jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_closure_check WHERE PROJECT_ID=?", String.class, PRJ));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE ID=?", String.class, approvalId));
        assertEquals("SKIPPED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval_task WHERE APPR_ID=?",
                String.class, approvalId));
    }

    // ==================== ②组：审批回调 / 遗留 / 结算 / 守卫 ====================

    @Test
    void conditionalPassActivatesRemediations() {
        makeAllMandatoryPass();
        seedSettlement(PRJ);
        seedAttach(PRJ);
        service.submitForApproval(PRJ);
        String checkId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_closure_check WHERE PROJECT_ID=?", String.class, PRJ);
        // PMO 登记遗留草稿
        asRole("ROLE_PMO");
        service.saveRemediationDrafts(PRJ, List.of(
                Map.of("content", "备件盘点交接", "ownerId", "clc-owner", "ownerName", "张三")));
        // 审批通过（模拟回调）
        String apprId = jdbc.queryForObject(
                "SELECT APPROVAL_ID FROM erp_pm_closure_check WHERE ID=?", String.class, checkId);
        callback.onApproved(instance(apprId, PRJ, checkId));
        // 遗留 OPEN +90 天、项目 CLOSED、条件标记
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_closure_remediation WHERE CHECK_ID=?",
                String.class, checkId));
        assertNotNull(jdbc.queryForObject(
                "SELECT DUE_DATE FROM erp_pm_closure_remediation WHERE CHECK_ID=?",
                java.sql.Timestamp.class, checkId));
        assertEquals("1", jdbc.queryForObject(
                "SELECT CONDITIONAL_FLAG FROM erp_pm_closure_check WHERE ID=?",
                String.class, checkId));
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project WHERE ID=?", String.class, PRJ));
        // 资源释放清单快照
        String snap = jdbc.queryForObject(
                "SELECT RES_RELEASE_JSON FROM erp_pm_closure_check WHERE ID=?",
                String.class, checkId);
        assertNotNull(snap, "释放清单快照已生成");
    }

    @Test
    void unconditionalPassNoRemediation() {
        makeAllMandatoryPass();
        seedSettlement(PRJ);
        seedAttach(PRJ);
        service.submitForApproval(PRJ);
        String checkId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_closure_check WHERE PROJECT_ID=?", String.class, PRJ);
        String apprId = jdbc.queryForObject(
                "SELECT APPROVAL_ID FROM erp_pm_closure_check WHERE ID=?", String.class, checkId);
        callback.onApproved(instance(apprId, PRJ, checkId));
        assertEquals("0", jdbc.queryForObject(
                "SELECT CONDITIONAL_FLAG FROM erp_pm_closure_check WHERE ID=?",
                String.class, checkId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_closure_remediation WHERE CHECK_ID=?",
                Integer.class, checkId), "无条件通过无遗留工单");
    }

    @Test
    void rejectReturnsActiveAndUnlocks() {
        makeAllMandatoryPass();
        seedSettlement(PRJ);
        seedAttach(PRJ);
        service.submitForApproval(PRJ);
        String checkId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_closure_check WHERE PROJECT_ID=?", String.class, PRJ);
        String apprId = jdbc.queryForObject(
                "SELECT APPROVAL_ID FROM erp_pm_closure_check WHERE ID=?", String.class, checkId);
        callback.onRejected(instance(apprId, PRJ, checkId));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_project WHERE ID=?", String.class, PRJ));
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_closure_check WHERE ID=?", String.class, checkId));
    }

    @Test
    void settlementConfirmUnfreezesAndSnapshot() {
        jdbc.update("UPDATE erp_pm_project SET FREEZE_FLAG='1', FREEZE_REASON='超支', "
                + "FROZEN_AT=NOW() WHERE ID=?", PRJ);
        asRole("ROLE_FINANCE");
        Map<String, Object> out = settlement.confirm(PRJ);
        assertNotNull(out.get("settled"), "快照已生成");
        assertEquals("0", jdbc.queryForObject(
                "SELECT FREEZE_FLAG FROM erp_pm_project WHERE ID=?", String.class, PRJ), "冻结解除");
        assertEquals("1", jdbc.queryForObject(
                "SELECT UNFROZEN_FLAG FROM erp_pm_closure_settlement WHERE PROJECT_ID=?",
                String.class, PRJ));
        // 幂等：二次确认 409
        ServiceException e = assertThrows(ServiceException.class, () -> settlement.confirm(PRJ));
        assertEquals(409, e.getCode());
    }

    @Test
    void settlementForbiddenForPm() {
        asRole("ROLE_PM");
        ServiceException e = assertThrows(ServiceException.class, () -> settlement.confirm(PRJ));
        assertEquals(403, e.getCode());
    }

    @Test
    void guardFiveHooks() {
        // CLOSED：五挂点全拦
        jdbc.update("UPDATE erp_pm_project SET STATUS='CLOSED' WHERE ID=?", PRJ);
        for (boolean strict : new boolean[]{true, false}) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> guard.assertWritable(PRJ, strict));
            assertEquals(422, e.getCode());
            assertTrue(e.getMessage().contains("已归档"), e.getMessage());
        }
        // CLOSING：变更挂点拦（strict=false），工时/成本放行（strict=true）
        jdbc.update("UPDATE erp_pm_project SET STATUS='CLOSING' WHERE ID=?", PRJ);
        ServiceException e = assertThrows(ServiceException.class,
                () -> guard.assertWritable(PRJ, false));
        assertTrue(e.getMessage().contains("撤回"), e.getMessage());
        guard.assertWritable(PRJ, true); // 不抛 = 放行
        // ACTIVE / 空项目：全放行
        jdbc.update("UPDATE erp_pm_project SET STATUS='ACTIVE' WHERE ID=?", PRJ);
        guard.assertWritable(PRJ, false);
        guard.assertWritable(null, true);
    }

    @Test
    void overdueRemediationEscalatesIdempotent() {
        makeAllMandatoryPass();
        seedSettlement(PRJ);
        seedAttach(PRJ);
        service.submitForApproval(PRJ);
        String checkId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_closure_check WHERE PROJECT_ID=?", String.class, PRJ);
        asRole("ROLE_PMO");
        service.saveRemediationDrafts(PRJ, List.of(
                Map.of("content", "遗留盘点", "ownerId", "clc-owner")));
        // 直接造 OPEN 且逾期的遗留工单
        jdbc.update("UPDATE erp_pm_closure_remediation SET STATUS='OPEN', ESCALATED_FLAG='0', "
                + "DUE_DATE=DATE_SUB(NOW(), INTERVAL 3 DAY) WHERE CHECK_ID=?", checkId);
        dailyTask.run();
        assertEquals("1", jdbc.queryForObject(
                "SELECT ESCALATED_FLAG FROM erp_pm_closure_remediation WHERE CHECK_ID=?",
                String.class, checkId));
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmClosureRemediationOverdue' "
                        + "AND BIZ_ID LIKE (SELECT CONCAT(ID,'%') FROM erp_pm_closure_remediation "
                        + "WHERE CHECK_ID=?)", Integer.class, checkId);
        assertTrue(notices >= 1, "升级通知已发");
        // 重复扫描幂等：不再新增通知
        int before = notices;
        dailyTask.run();
        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmClosureRemediationOverdue' "
                        + "AND BIZ_ID LIKE (SELECT CONCAT(ID,'%') FROM erp_pm_closure_remediation "
                        + "WHERE CHECK_ID=?)", Integer.class, checkId);
        assertEquals(before, after, "重复扫描不重复通知");
    }

    // ==================== 造数辅助 ====================

    /** 造齐强制项 PASS 环境：阶段完成 + IP 协议 + 成本=基线 + 两个人工项带附件 */
    private void makeAllMandatoryPass() {
        seedPhase("clc-ph-1", "1");
        seedIpAgreement();
        seedCost("clc-ph-1", "1000"); // = 基线 1000
        if (!hasCheck()) {
            service.initiate(PRJ);
        }
        for (String key : List.of("ACCEPT", "DOC")) {
            String id = itemIdOf(PRJ, key);
            seedAttach(id);
            service.confirmItem(id, Map.of("result", "PASS", "attachIds", "att-" + key));
        }
    }

    private boolean hasCheck() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_closure_check WHERE PROJECT_ID=?", Integer.class, PRJ);
        return n != null && n > 0;
    }

    private String itemIdOf(String projectId, String itemKey) {
        return jdbc.queryForObject(
                "SELECT i.ID FROM erp_pm_closure_check_item i "
                        + "JOIN erp_pm_closure_check c ON c.ID = i.CHECK_ID "
                        + "WHERE c.PROJECT_ID=? AND i.ITEM_KEY=?",
                String.class, projectId, itemKey);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemByKey(List<Map<String, Object>> items, String key) {
        return items.stream()
                .filter(i -> key.equals(i.get("itemKey"))).findFirst().orElseThrow();
    }

    private void seedProject(String id, String status) {
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'CLC 结项项目', '研发', 'INTERNAL', 'NS-CLC', 'clc-pm', "
                + "DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 10 DAY), "
                + "?, '1', 1000, 0.10, '0', '0', 'junit', NOW(), '0', 0)", id, status);
    }

    private void seedPhase(String id, String done) {
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "PHASE_DONE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, ?, 1, "
                + "'开发阶段', ?, 'junit', NOW(), '0', 0)", id, PRJ, id.substring(4), done);
    }

    private void seedMsApproved() {
        jdbc.update("INSERT INTO erp_pm_milestone (ID, PROJECT_ID, WBS_ID, NAME, PLAN_END_DATE, "
                + "STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES ('clc-ms-a', ?, "
                + "'clc-ph-1', '立项里程碑', DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'APPROVED', "
                + "'junit', NOW(), '0', 0)", PRJ);
    }

    private void seedIpAgreement() {
        jdbc.update("INSERT INTO erp_pm_ip_agreement (ID, PROJECT_ID, COOP_MODE, COMPANY_RATE, "
                + "SIGN_DATE, EFFECTIVE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('clc-ip-1', ?, 'INDEPENDENT', 1.00, CURDATE(), '1', 'junit', NOW(), '0', 0)", PRJ);
    }

    private void seedCost(String wbsId, String amount) {
        jdbc.update("INSERT INTO erp_pm_cost_collect (ID, PROJECT_ID, WBS_ID, COST_TYPE, AMOUNT, "
                + "SOURCE_TYPE, SOURCE_ID, COLLECT_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('clc-cost-1', ?, ?, 'LABOR', ?, 'MANUAL', 'clc-src-1', CURDATE(), "
                + "'junit', NOW(), '0', 0)", PRJ, wbsId, amount);
    }

    private void seedSettlement(String projectId) {
        jdbc.update("INSERT INTO erp_pm_closure_settlement (ID, PROJECT_ID, COLLECT_TOTAL, "
                + "BASELINE_AMT, DEVIATION_RATE, CONFIRM_BY, CONFIRM_DATE, CREATE_BY, CREATE_DATE, "
                + "DEL_FLAG, VER_NO) VALUES ('clc-set-1', ?, 1000, 1000, 0, 'clc-fin', NOW(), "
                + "'junit', NOW(), '0', 0)", projectId);
    }

    private void seedAttach(String bizId) {
        jdbc.update("INSERT INTO erp_sys_attachment (ID, BIZ_TYPE, BIZ_ID, FILE_NAME, STORE_PATH, "
                + "FILE_SIZE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "(UUID_SHORT(), 'PROJECT_CLOSURE', ?, ?, ?, 10, 'junit', NOW(), '0', 0)",
                bizId, "凭证-" + bizId + "-" + System.nanoTime() + ".pdf",
                "clc-" + System.nanoTime() + ".pdf");
    }

    private com.erp.entity.system.ApprovalInstance instance(String apprId, String projectId,
                                                            String checkId) {
        var inst = new com.erp.entity.system.ApprovalInstance();
        inst.setId(apprId);
        inst.setBizType("ProjectClosure");
        inst.setBizId(projectId);
        inst.setStatus("APPROVED");
        return inst;
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='PmClosureRemediationOverdue' "
                + "AND BIZ_ID LIKE (SELECT CONCAT(ID,'%') FROM (SELECT ID FROM "
                + "erp_pm_closure_remediation WHERE PROJECT_ID LIKE 'clc-%') t)");
        jdbc.update("DELETE FROM erp_pm_closure_check_item WHERE CHECK_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_closure_check WHERE PROJECT_ID LIKE 'clc-%') t)");
        jdbc.update("DELETE FROM erp_pm_closure_remediation WHERE PROJECT_ID LIKE 'clc-%'");
        jdbc.update("DELETE FROM erp_pm_closure_settlement WHERE PROJECT_ID LIKE 'clc-%'");
        jdbc.update("DELETE FROM erp_pm_closure_check WHERE PROJECT_ID LIKE 'clc-%'");
        jdbc.update("DELETE t FROM erp_sys_approval_task t INNER JOIN erp_sys_approval a "
                + "ON t.APPR_ID = a.ID WHERE a.BIZ_ID LIKE 'clc-%'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'clc-%'");
        jdbc.update("DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID LIKE 'clc-%'");
        jdbc.update("DELETE FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'clc-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'clc-%'");
        jdbc.update("DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID LIKE 'clc-%'");
        // 附件 bizId 含 UUID（检查项行），按 BIZ_TYPE 整体清理（该类型当前仅结项域使用）
        jdbc.update("DELETE FROM erp_sys_attachment WHERE BIZ_TYPE='PROJECT_CLOSURE'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'clc-%'");
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "clc-tester", "x", roles));
    }
}
