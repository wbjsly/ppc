package com.erp.system;

import com.erp.service.BreakGlassService;
import com.erp.service.approval.ApprovalEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.erp.common.ServiceException;

/**
 * 紧急权限全链（add-break-glass-console spec：SOP-5.6-C）：
 * 工单处理中阻断 / 模板特权校验 / 双签缺一 / 全过即刻生效（豁免窗口）/
 * 提前归还（豁免基线守卫）+ 审计生成 / 签署闭环 / 逾期告警 / 专项评审。
 */
@SpringBootTest
class BreakGlassDbTest {

    @Autowired
    private BreakGlassService service;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    private String applicant;
    private String target;
    private String dutyMgr;
    private String secAdmin;
    private String incidentId;
    private String templateId;

    @BeforeEach
    void setUp() {
        purge();
        applicant = seedUser("bgd-app");
        target = seedUser("bgd-tgt");
        dutyMgr = seedUser("bgd-duty");
        secAdmin = seedUser("bgd-sec");
        // 工单（处理中）
        Map<String, Object> inc = service.incidentSave(Map.of(
                "incNo", "INC-BGD-" + UUID.randomUUID().toString().substring(0, 8),
                "title", "冒烟数据库死锁故障"));
        incidentId = String.valueOf(inc.get("id"));
        // 模板（非特权角色集合）
        Map<String, Object> tpl = service.templateSave(Map.of(
                "code", "BGD-TPL-" + UUID.randomUUID().toString().substring(0, 6),
                "name", "测试应急模板", "rolesJson", "[\"ROLE_PLANNER\"]"));
        templateId = String.valueOf(tpl.get("id"));
        // 双签人角色
        grantRole(dutyMgr, "ROLE_DUTY_MGR");
        grantRole(secAdmin, "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.execute("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='BreakGlassRequest' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_bg_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'bgd-%') u)) r)");
        jdbc.execute("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='BreakGlassRequest' "
                + "AND BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_bg_request "
                + "WHERE TARGET_USER_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'bgd-%') u)) r)");
        jdbc.execute("DELETE FROM erp_admin_bg_audit WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'bgd-%') t)");
        jdbc.execute("DELETE FROM erp_admin_grant_ledger WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'bgd-%') t)");
        jdbc.execute("DELETE FROM erp_admin_bg_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'bgd-%') t)");
        jdbc.execute("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'bgd-%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'bgd-%'");
        jdbc.execute("DELETE FROM erp_admin_bg_template WHERE CODE LIKE 'BGD-TPL-%'");
        jdbc.execute("DELETE FROM erp_ops_incident WHERE INC_NO LIKE 'INC-BGD-%'");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'bgd-%') t); SET @audit_bypass=0");
        SecurityContextHolder.clearContext();
    }

    private String seedUser(String name) {
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','bgd-test','0',0)",
                id, name, "{noop}", "BG测试");
        return id;
    }

    private void grantRole(String userId, String roleCode) {
        jdbc.update("INSERT INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) "
                        + "SELECT ?,?,ID FROM erp_admin_role WHERE ROLE_CODE=?",
                "bgd-ur-" + UUID.randomUUID().toString().substring(0, 8), userId, roleCode);
    }

    private void login(String userId, String... roles) {
        var authorities = java.util.stream.Stream.of(roles)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, authorities));
    }

    private long taskCount(String reqId, String... roles) {
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < roles.length; i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                        + "ON t.APPR_ID=i.ID WHERE i.BIZ_ID=? AND t.ROLE_REQUIRED IN (" + in + ")",
                Long.class, concat(reqId, roles));
        return cnt == null ? 0 : cnt;
    }

    private static Object[] concat(String first, String... rest) {
        Object[] args = new Object[rest.length + 1];
        args[0] = first;
        System.arraycopy(rest, 0, args, 1, rest.length);
        return args;
    }

    private void signActive(String reqId, String signer, String role) {
        login(signer, role);
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON i.ID=t.APPR_ID "
                        + "WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE' AND t.ROLE_REQUIRED=? LIMIT 1",
                String.class, reqId, role);
        assertNotNull(taskId, "应存在待签节点 " + role);
        engine.pass(taskId, "测试签署同意");
    }

    // ---------- 2.x 模板与工单 ----------

    @Test
    void templateRejectsPrivilegedRoles() {
        ServiceException e = assertThrows(ServiceException.class, () -> service.templateSave(
                Map.of("code", "BGD-BAD", "name", "违规模板", "rolesJson", "[\"ROLE_ADMIN\"]")));
        assertEquals(422, e.getCode());
    }

    @Test
    void incidentLifecycle() {
        login(applicant, "ROLE_ADMIN");
        Map<String, Object> inc = service.incidentSave(Map.of(
                "incNo", "INC-BGD-TRV", "title", "工单流转测试"));
        String id = String.valueOf(inc.get("id"));
        service.incidentTransition(id, "CLOSED");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_ops_incident WHERE ID=?",
                String.class, id);
        assertEquals("CLOSED", st);
    }

    // ---------- 3.x 申请与双签 ----------

    @Test
    void submitBlockedWithoutProcessingIncident() {
        login(applicant, "ROLE_ADMIN");
        // 无工单
        ServiceException e1 = assertThrows(ServiceException.class, () -> service.submit(
                target, templateId, null, 2, "YES", "紧急处置需要足够长度的说明"));
        assertEquals(422, e1.getCode());
        // 工单已关闭
        service.incidentTransition(incidentId, "CLOSED");
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.submit(
                target, templateId, incidentId, 2, "YES", "紧急处置需要足够长度的说明"));
        assertEquals(422, e2.getCode());
    }

    @Test
    void submitValidations() {
        login(applicant, "ROLE_ADMIN");
        // 时长超上限（默认 2）
        ServiceException e1 = assertThrows(ServiceException.class, () -> service.submit(
                target, templateId, incidentId, 5, "YES", "紧急处置需要足够长度的说明"));
        assertEquals(422, e1.getCode());
        // 录屏声明缺失
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.submit(
                target, templateId, incidentId, 2, null, "紧急处置需要足够长度的说明"));
        assertEquals(422, e2.getCode());
        // 说明过短
        ServiceException e3 = assertThrows(ServiceException.class, () -> service.submit(
                target, templateId, incidentId, 2, "YES", "处理"));
        assertEquals(422, e3.getCode());
        // 停用模板
        jdbc.update("UPDATE erp_admin_bg_template SET STATUS='0' WHERE ID=?", templateId);
        ServiceException e4 = assertThrows(ServiceException.class, () -> service.submit(
                target, templateId, incidentId, 2, "YES", "紧急处置需要足够长度的说明"));
        assertEquals(422, e4.getCode());
    }

    @Test
    void dualSignChainAndFullPassActivation() {
        login(applicant, "ROLE_ADMIN");
        Map<String, Object> out = service.submit(target, templateId, incidentId,
                2, "YES", "生产死锁抢修需要应急权限处置");
        String reqId = String.valueOf(out.get("id"));
        // 双签两节点
        assertEquals(2, taskCount(reqId, "ROLE_DUTY_MGR", "ROLE_ADMIN"));
        // 缺一不生效
        signActive(reqId, dutyMgr, "ROLE_DUTY_MGR");
        String st1 = jdbc.queryForObject("SELECT STATUS FROM erp_admin_bg_request WHERE ID=?",
                String.class, reqId);
        assertEquals("PENDING_APPROVAL", st1);
        Integer bound1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                        + "WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_PLANNER'", Integer.class, target);
        assertEquals(0, bound1, "缺第二签不得授予");
        // 第二签全过 → 即刻生效（豁免窗口：不种任何窗口也生效）
        signActive(reqId, secAdmin, "ROLE_ADMIN");
        String st2 = jdbc.queryForObject("SELECT STATUS FROM erp_admin_bg_request WHERE ID=?",
                String.class, reqId);
        assertEquals("ACTIVE", st2, "双签通过即刻生效（不等窗口）");
        Integer bound2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                        + "WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_PLANNER'", Integer.class, target);
        assertEquals(1, bound2);
        // 台账 SOURCE=BREAK_GLASS
        String src = jdbc.queryForObject(
                "SELECT SOURCE FROM erp_admin_grant_ledger WHERE APPLY_ID=? LIMIT 1",
                String.class, reqId);
        assertEquals("BREAK_GLASS", src);
    }

    @Test
    void rejectionVoidsWithoutGrant() {
        login(applicant, "ROLE_ADMIN");
        Map<String, Object> out = service.submit(target, templateId, incidentId,
                2, "YES", "生产故障抢修需要应急权限处置");
        String reqId = String.valueOf(out.get("id"));
        signActive(reqId, dutyMgr, "ROLE_DUTY_MGR");
        // 安全管理员驳回（意见 ≥2 字）
        login(secAdmin, "ROLE_ADMIN");
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON i.ID=t.APPR_ID "
                        + "WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE' LIMIT 1", String.class, reqId);
        engine.reject(taskId, "处置说明不充分");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_admin_bg_request WHERE ID=?",
                String.class, reqId);
        assertEquals("REJECTED", st);
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                        + "WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_PLANNER'", Integer.class, target);
        assertEquals(0, bound, "驳回不授予");
    }

    @Test
    void selfSignBlocked() {
        login(applicant, "ROLE_ADMIN");
        // 申请人持有 ROLE_ADMIN（安全管理员节点），自签应 403
        grantRole(applicant, "ROLE_ADMIN");
        Map<String, Object> out = service.submit(target, templateId, incidentId,
                2, "YES", "自签阻断验证的应急处置说明");
        String reqId = String.valueOf(out.get("id"));
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON i.ID=t.APPR_ID "
                        + "WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE' AND t.ROLE_REQUIRED='ROLE_ADMIN'",
                String.class, reqId);
        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.pass(taskId, "自签尝试"));
        assertEquals(403, e.getCode());
    }

    // ---------- 4.x 回收与审计 ----------

    @Test
    void earlyReclaimExemptsBaselineGuardAndGeneratesAudit() {
        // 目标用户岗位基线含 ROLE_PLANNER（模拟基线内角色）——BG 回收仍须删除投影
        String posId = "bgd-pos-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_admin_position_baseline (ID,POS_ID,ROLE_CODES,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,'[\"ROLE_PLANNER\"]','bgd-test','0',0)",
                "bgd-bl-" + UUID.randomUUID().toString().substring(0, 8), posId);
        jdbc.update("INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,EMP_STATUS,HIRE_DATE,"
                        + "USER_ID,POS_ID,ATTEND_SCHEME,CREATE_BY,DEL_FLAG,VER_NO) "
                        + "VALUES (?,?,?,?, '在职', CURDATE(), ?, ?, 'STD','bgd-test','0',0)",
                "bgd-emp-" + UUID.randomUUID().toString().substring(0, 8),
                "BGDE" + UUID.randomUUID().toString().substring(0, 6), "BG员工",
                "BGDID-" + UUID.randomUUID().toString().substring(0, 8), target, posId);

        login(applicant, "ROLE_ADMIN");
        Map<String, Object> out = service.submit(target, templateId, incidentId,
                2, "YES", "提前归还与审计链验证说明");
        String reqId = String.valueOf(out.get("id"));
        signActive(reqId, dutyMgr, "ROLE_DUTY_MGR");
        signActive(reqId, secAdmin, "ROLE_ADMIN");
        String ledgerId = jdbc.queryForObject(
                "SELECT GRANT_ID FROM erp_admin_bg_request WHERE ID=?", String.class, reqId);
        assertNotNull(ledgerId);

        // 提前归还（豁免基线守卫：即使基线含 ROLE_PLANNER 也删除投影）
        Map<String, Object> rc = service.reclaim(reqId);
        assertEquals(true, rc.get("reclaimed"));
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role WHERE USER_ID=? "
                        + "AND ROLE_ID=(SELECT ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_PLANNER')",
                Integer.class, target);
        assertEquals(0, bound, "BG 回收豁免基线守卫，投影须删除");
        String reqSt = jdbc.queryForObject("SELECT STATUS FROM erp_admin_bg_request WHERE ID=?",
                String.class, reqId);
        assertEquals("RECLAIMED", reqSt);
        // 审计自动生成（LEDGER_ID 幂等）
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_bg_audit WHERE LEDGER_ID=?", Integer.class, ledgerId);
        assertEquals(1, audits);
        // 重复归还幂等
        Map<String, Object> rc2 = service.reclaim(reqId);
        assertEquals(false, rc2.get("reclaimed"));
        Integer audits2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_bg_audit WHERE LEDGER_ID=?", Integer.class, ledgerId);
        assertEquals(1, audits2, "审计不重复生成");
    }

    @Test
    void signAuditAndOverdueSweep() {
        login(applicant, "ROLE_ADMIN");
        Map<String, Object> out = service.submit(target, templateId, incidentId,
                2, "YES", "审计签署与逾期告警验证说明");
        String reqId = String.valueOf(out.get("id"));
        signActive(reqId, dutyMgr, "ROLE_DUTY_MGR");
        signActive(reqId, secAdmin, "ROLE_ADMIN");
        service.reclaim(reqId);
        String auditId = jdbc.queryForObject(
                "SELECT ID FROM erp_admin_bg_audit WHERE APPLY_ID=?", String.class, reqId);

        // 逾期告警：回拨回收时间至 25h 前 → 提醒一次；12h 内重复扫描不重复提醒
        jdbc.update("UPDATE erp_admin_bg_audit SET RECLAIM_AT=DATE_SUB(NOW(), INTERVAL 25 HOUR) "
                + "WHERE ID=?", auditId);
        Map<String, Object> sw1 = service.sweepAuditOverdue();
        assertTrue(((Number) sw1.get("reminded")).intValue() >= 1);
        Map<String, Object> sw2 = service.sweepAuditOverdue();
        assertEquals(0, ((Number) sw2.get("reminded")).intValue(), "12h 内不重复提醒");
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_ID=? AND TITLE LIKE '%逾期%'",
                Integer.class, reqId);
        assertTrue(notices >= 1, "逾期告警站内通知");

        // 签署闭环
        login(secAdmin, "ROLE_ADMIN");
        service.signAudit(auditId, "NO_VIOLATION");
        String concl = jdbc.queryForObject("SELECT CONCLUSION FROM erp_admin_bg_audit WHERE ID=?",
                String.class, auditId);
        assertEquals("NO_VIOLATION", concl);
        assertNotNull(jdbc.queryForObject("SELECT SIGNED_AT FROM erp_admin_bg_audit WHERE ID=?",
                java.sql.Timestamp.class, auditId));
        // 重复签署拒绝
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.signAudit(auditId, "VIOLATION"));
        assertEquals(422, e.getCode());
        // 申请单置 AUDITED
        String reqSt = jdbc.queryForObject("SELECT STATUS FROM erp_admin_bg_request WHERE ID=?",
                String.class, reqId);
        assertEquals("AUDITED", reqSt);
        // 签署后不再提醒
        Map<String, Object> sw3 = service.sweepAuditOverdue();
        assertEquals(0, ((Number) sw3.get("reminded")).intValue());
    }

    @Test
    void monthlyThirdGrantMarksSpecialReview() {
        for (int i = 1; i <= 3; i++) {
            login(applicant, "ROLE_ADMIN"); // 每轮重置：signActive 会覆盖 SecurityContext
            String targetU = seedUser("bgd-h" + i);
            Map<String, Object> out = service.submit(targetU, templateId, incidentId,
                    1, "YES", "月度高频专项评审验证说明第" + i + "次");
            String reqId = String.valueOf(out.get("id"));
            signActive(reqId, dutyMgr, "ROLE_DUTY_MGR");
            signActive(reqId, secAdmin, "ROLE_ADMIN");
            service.reclaim(reqId);
            String flag = jdbc.queryForObject(
                    "SELECT SPECIAL_REVIEW FROM erp_admin_bg_audit WHERE APPLY_ID=?",
                    String.class, reqId);
            if (i < 3) {
                assertEquals("0", flag, "前两次不标记");
            } else {
                assertEquals("1", flag, "当月第 3 次标记专项评审");
            }
        }
    }
}
