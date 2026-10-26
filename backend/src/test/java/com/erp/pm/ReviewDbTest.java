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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 交付评审与审批 DbTest（milestone-management spec：交付物门禁 / 三态必填 /
 * 返工升级 GM / 审批联动 DONE+阶段回写 / 驳回回 REWORK）。
 * 造数前缀 trv- / 项目名 TRV%。
 */
@SpringBootTest
class ReviewDbTest {

    @Autowired
    private PmMilestoneService milestoneService;
    @Autowired
    private com.erp.service.impl.pm.PmMilestoneApprovalCallback callback;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String phaseId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("trv-user-pm", "trvpm", "TRV 项目经理");
        projectId = seedProject();
        phaseId = seedWbsPhase();
        authAs("trv-junit", "PMO");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void deliverableGateAndSubmitFlow() {
        String id = newMilestone("TRV 门禁");
        // 无附件 submit → 422
        ServiceException e = assertThrows(ServiceException.class, () -> milestoneService.submit(id));
        assertTrue(e.getMessage().contains("交付物"), e.getMessage());
        // 补附件 → submit 放行 → REVIEWING
        attach(id, "trv-att-1");
        milestoneService.submit(id);
        assertEquals("REVIEWING", statusOf(id));
        // 重复 submit（REVIEWING 非 PLANNED/REWORK）→ 422 非法转移
        ServiceException e2 = assertThrows(ServiceException.class, () -> milestoneService.submit(id));
        assertEquals(422, e2.getCode());
    }

    @Test
    void preconditionGateOnSubmit() {
        String a = newMilestone("TRV 前序");
        String b = newMilestone("TRV 后序");
        // B 依赖 A；A 未 DONE 时 B submit → 422
        milestoneService.update(b, Map.of("prevMilestoneId", a));
        attach(b, "trv-att-b");
        ServiceException e = assertThrows(ServiceException.class, () -> milestoneService.submit(b));
        assertTrue(e.getMessage().contains("前序"), e.getMessage());
        assertNotNull(a);
    }

    @Test
    void conditionalRequiredFields() {
        String id = newMilestone("TRV 有条件");
        attach(id, "trv-att-c");
        milestoneService.submit(id);
        // 缺整改期限 → 422
        ServiceException e = assertThrows(ServiceException.class, () ->
                milestoneService.review(id, Map.of("result", "CONDITIONAL",
                        "rectifyItem", "补齐测试报告")));
        assertEquals(422, e.getCode());
        // 补全 → 保持 REVIEWING（可重提）
        milestoneService.review(id, Map.of("result", "CONDITIONAL",
                "rectifyItem", "补齐测试报告", "rectifyDue", LocalDate.now().plusDays(7).toString()));
        assertEquals("REVIEWING", statusOf(id));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_ms_review WHERE MILESTONE_ID=? AND RESULT='CONDITIONAL'",
                Integer.class, id), "评审记录留存");
    }

    @Test
    void failReworkAndEscalateAtSecond() {
        String id = newMilestone("TRV 返工");
        attach(id, "trv-att-f");
        milestoneService.submit(id);
        // 缺返工计划 → 422
        ServiceException e = assertThrows(ServiceException.class, () ->
                milestoneService.review(id, Map.of("result", "FAIL")));
        assertEquals(422, e.getCode());
        // 第 1 次 FAIL → REWORK + 计数 1；无 GM 通知
        milestoneService.review(id, Map.of("result", "FAIL", "reworkPlan", "补齐材料后重测"));
        assertEquals("REWORK", statusOf(id));
        assertEquals(1, reworkCountOf(id));
        assertEquals(0, gmNoticeCount(id), "第 1 次不升级");
        // 重提 → 第 2 次 FAIL → 计数 2 + GM 通知
        milestoneService.submit(id);
        milestoneService.review(id, Map.of("result", "FAIL", "reworkPlan", "二次返工"));
        assertEquals(2, reworkCountOf(id));
        assertEquals(1, gmNoticeCount(id), "第 2 次自动升级 GM");
    }

    @Test
    void approvalPassLinksDoneAndPhaseWriteBack() {
        String m1 = newMilestone("TRV 里程碑1");
        String m2 = newMilestone("TRV 里程碑2");
        attach(m1, "trv-att-p1");
        milestoneService.submit(m1);
        milestoneService.review(m1, Map.of("result", "PASS"));
        assertEquals("APPROVED", statusOf(m1));
        // 审批实例已发起（单节点 PMO）
        String apprId = jdbc.queryForObject(
                "SELECT APPR_ID FROM erp_pm_milestone WHERE ID=?", String.class, m1);
        assertNotNull(apprId);
        // 底座通过 → DONE + 实际完成日；阶段尚有 m2 未 DONE → 不回写
        passApproval(apprId);
        assertEquals("DONE", statusOf(m1));
        assertNotNull(jdbc.queryForObject(
                "SELECT ACTUAL_END_DATE FROM erp_pm_milestone WHERE ID=?", LocalDate.class, m1));
        assertEquals("0", phaseDoneOf(phaseId), "阶段尚有未完成里程碑");
        // m2 也过 → 阶段回写 PHASE_DONE=1
        attach(m2, "trv-att-p2");
        milestoneService.submit(m2);
        milestoneService.review(m2, Map.of("result", "PASS"));
        String apprId2 = jdbc.queryForObject(
                "SELECT APPR_ID FROM erp_pm_milestone WHERE ID=?", String.class, m2);
        passApproval(apprId2);
        assertEquals("DONE", statusOf(m2));
        assertEquals("1", phaseDoneOf(phaseId), "阶段名下全部 DONE → 回写完成");
    }

    @Test
    void approvalRejectBackToRework() {
        String id = newMilestone("TRV 驳回");
        attach(id, "trv-att-r");
        milestoneService.submit(id);
        milestoneService.review(id, Map.of("result", "PASS"));
        String apprId = jdbc.queryForObject(
                "SELECT APPR_ID FROM erp_pm_milestone WHERE ID=?", String.class, id);
        rejectApproval(apprId, "证据不足退回");
        assertEquals("REWORK", statusOf(id));
        assertEquals("证据不足退回",
                jdbc.queryForObject("SELECT REJECT_OPINION FROM erp_pm_milestone WHERE ID=?",
                        String.class, id));
    }

    // ---------- helpers ----------

    private String newMilestone(String name) {
        return String.valueOf(milestoneService.create(Map.of(
                "projectId", projectId, "wbsId", phaseId, "name", name,
                "planEndDate", LocalDate.now().plusDays(45).toString())).get("id"));
    }

    private void attach(String milestoneId, String attId) {
        jdbc.update("INSERT INTO erp_sys_attachment (ID, BIZ_TYPE, BIZ_ID, FILE_NAME, STORE_PATH, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, 'MILESTONE_DELIV', ?, 'd.pdf', "
                + "'/tmp/d.pdf', 'junit', NOW(), '0', 0)", attId, milestoneId);
    }

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

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_pm_milestone WHERE ID=?", String.class, id);
    }

    private int reworkCountOf(String id) {
        return jdbc.queryForObject("SELECT REWORK_COUNT FROM erp_pm_milestone WHERE ID=?",
                Integer.class, id);
    }

    private int gmNoticeCount(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_sys_notice "
                + "WHERE TARGET_ROLE='ROLE_GM' AND BIZ_TYPE='PmMilestone' AND BIZ_ID=?",
                Integer.class, id);
    }

    private String phaseDoneOf(String wbsId) {
        return jdbc.queryForObject("SELECT PHASE_DONE FROM erp_pm_wbs WHERE ID=?", String.class, wbsId);
    }

    private String seedProject() {
        String id = "tvs-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'TRV 评审项目', '研发', 'INTERNAL', 'NS-TRV', 'trv-user-pm', "
                + "'2026-10-01', '2027-06-30', 'ACTIVE', '0', 0, 0.10, '0', 'junit', NOW(), '0', 0)",
                id);
        return id;
    }

    private String seedWbsPhase() {
        String id = "tvs-wbs-phase";
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'W1PH', 1, 'TRV 阶段', "
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
        jdbc.update("DELETE FROM erp_pm_ms_review WHERE MILESTONE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tvs-%') t)");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE='PmMilestone' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tvs-%') t)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PmMilestone' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tvs-%') t)");
        jdbc.update("DELETE FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tvs-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmMilestone%' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_milestone WHERE PROJECT_ID LIKE 'tvs-%') t) "
                + "OR (TARGET_ROLE='ROLE_GM' AND BIZ_TYPE='PmMilestone')");
        jdbc.update("DELETE FROM erp_sys_attachment WHERE BIZ_TYPE='MILESTONE_DELIV' "
                + "AND BIZ_ID LIKE '%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'tvs-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'tvs-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='trv-user-pm'");
    }
}
