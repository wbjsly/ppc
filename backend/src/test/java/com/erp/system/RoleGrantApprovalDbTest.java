package com.erp.system;

import com.erp.security.JwtTokenProvider;
import com.erp.service.approval.ApprovalEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 超基线例外改道（add-permission-approval-flow spec：超基线例外统一入口，D7 吸收
 * RoleGrantException；role-permission-management MODIFIED 场景）：
 * adjust-roles 超基线 → KIND=EXCEPTION 申请单 + 风险路由审批 → 通过生效落台账。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoleGrantApprovalDbTest {

    private static final String ACCOUNTS = "/api/system/accounts";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApprovalEngine approvalEngine;

    private String adminToken;
    private String target;
    private String approver;
    private String plannerRoleId;
    private String finMgrRoleId;

    @BeforeEach
    void setUp() {
        purge();
        adminToken = jwtTokenProvider.generateToken("rga-admin",
                Map.of("roles", List.of("ROLE_ADMIN")), 5 * 60 * 1000L);
        plannerRoleId = roleId("ROLE_PLANNER");
        finMgrRoleId = roleId("ROLE_FINANCE_MGR");
        target = seedUserWithBaseline("rga-u1", "[\"ROLE_PLANNER\"]");
        // 审批人：持 ROLE_DEPT_HEAD（例外链 = 主管缺位回落节点），且非申请人本人
        approver = seedUser("rga-approver", "ROLE_DEPT_HEAD");
        // 例外目标含特权角色（FIN_MGR）→ 生效须在变更窗口内（design D5），种全天窗口
        int day = java.time.LocalDateTime.now().getDayOfWeek().getValue();
        jdbc.update("INSERT INTO erp_change_window (ID,NAME,DAY_OF_WEEK,START_TIME,END_TIME,STATUS,"
                        + "CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES ('rga-win-1','RG全天窗口',?,'00:00:00',"
                        + "'23:59:59','1','rga-test',NOW(),'0',0)", day);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.execute("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PermRequest' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'rga-%') u)) r)");
        jdbc.execute("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PermRequest' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'rga-%') u)) r)");
        jdbc.execute("DELETE FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'rga-%') t)");
        jdbc.execute("DELETE FROM erp_admin_grant_ledger WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'rga-%') t)");
        jdbc.execute("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'rga-%'");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'rga-%') t); SET @audit_bypass=0");
        jdbc.execute("DELETE FROM erp_sys_notice WHERE TARGET_USER IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'rga-%') t)");
        jdbc.execute("DELETE FROM erp_admin_position_baseline WHERE POS_ID LIKE 'rga-pos%'");
        jdbc.execute("DELETE FROM erp_change_window WHERE ID LIKE 'rga-win%'");
        jdbc.execute("DELETE FROM hr_employee WHERE ID LIKE 'rga-emp%' OR EMP_NO LIKE 'RGA%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'rga-%'");
        SecurityContextHolder.clearContext();
    }

    private String roleId(String code) {
        return jdbc.queryForObject("SELECT ID FROM erp_admin_role WHERE ROLE_CODE = ?",
                String.class, code);
    }

    private String seedUser(String username, String roleCode) {
        String uid = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','rga-test','0',0)",
                uid, username, "{noop}", "例外改道测试用户");
        jdbc.update("INSERT INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) SELECT ?,?,ID "
                + "FROM erp_admin_role WHERE ROLE_CODE=?",
                "rga-ur-" + UUID.randomUUID().toString().substring(0, 8), uid, roleCode);
        return uid;
    }

    /** 目标用户 + 员工锚（POS 基线，无 REPORT_TO → 例外链回落 ROLE_DEPT_HEAD 节点） */
    private String seedUserWithBaseline(String username, String baselineJson) {
        String uid = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','rga-test','0',0)",
                uid, username, "{noop}", "例外改道测试用户");
        String posId = "rga-pos-" + username;
        String idCard = "11010119900101" + uid.substring(0, 4);
        jdbc.update("INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,EMP_STATUS,HIRE_DATE,"
                        + "USER_ID,POS_ID,ATTEND_SCHEME,CREATE_BY,DEL_FLAG,VER_NO) "
                        + "VALUES (?,?,?,?,'在职','2026-01-01',?,?,'STD','rga-test','0',0)",
                "rga-emp-" + username, "RGA" + username.substring(username.length() - 3),
                "例外改道员工", idCard, uid, posId);
        jdbc.update("INSERT INTO erp_admin_position_baseline (ID,POS_ID,ROLE_CODES,CREATE_BY,"
                        + "CREATE_DATE,DEL_FLAG,VER_NO) VALUES (?,?,?,'rga-test',NOW(),'0',0)",
                "rga-bl-" + username, posId, baselineJson);
        return uid;
    }

    private boolean userHasRole(String uid, String code) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r "
                        + "ON r.ID=ur.ROLE_ID WHERE ur.USER_ID=? AND r.ROLE_CODE=?",
                Integer.class, uid, code);
        return cnt != null && cnt > 0;
    }

    /** 走 adjust-roles 超基线路径产生 KIND=EXCEPTION 申请单，返回 requestId */
    private String createException(String reason) throws Exception {
        var res = mockMvc.perform(put(ACCOUNTS + "/" + target + "/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[\"" + plannerRoleId + "\",\"" + finMgrRoleId
                                + "\"],\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andReturn();
        String body = res.getResponse().getContentAsString();
        String requestId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("data").path("requestId").asText("");
        org.junit.jupiter.api.Assertions.assertFalse(requestId.isEmpty(),
                "响应缺 requestId：" + body);
        return requestId;
    }

    private String taskOf(String requestId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                        + "ON i.ID=t.APPR_ID WHERE i.BIZ_ID=? ORDER BY t.SEQ LIMIT 1",
                String.class, requestId);
    }

    @Test
    void baselineGrantAppliesImmediately() throws Exception {
        mockMvc.perform(put(ACCOUNTS + "/" + target + "/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[\"" + plannerRoleId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.after[0]").value("ROLE_PLANNER"));
        org.junit.jupiter.api.Assertions.assertTrue(userHasRole(target, "ROLE_PLANNER"),
                "基线内授予须直接生效");
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_perm_request WHERE TARGET_USER_ID=?",
                Integer.class, target);
        org.junit.jupiter.api.Assertions.assertEquals(0, pending, "基线内不产生申请单");
    }

    @Test
    void beyondBaselineCreatesExceptionRequest() throws Exception {
        String requestId = createException("需要财务对账协同权限");
        // 角色未生效
        org.junit.jupiter.api.Assertions.assertFalse(userHasRole(target, "ROLE_FINANCE_MGR"),
                "审批前角色不得生效");
        // KIND=EXCEPTION 申请单（吸收后新 carrier）
        Map<String, Object> req = jdbc.queryForMap(
                "SELECT KIND, STATUS, VALID_MONTHS, ROLES_JSON FROM erp_admin_perm_request "
                        + "WHERE ID=?", requestId);
        org.junit.jupiter.api.Assertions.assertEquals("EXCEPTION", req.get("KIND"));
        org.junit.jupiter.api.Assertions.assertEquals("PENDING_APPROVAL", req.get("STATUS"));
        org.junit.jupiter.api.Assertions.assertEquals(12, req.get("VALID_MONTHS"),
                "例外默认有效期 12 个月");
        org.junit.jupiter.api.Assertions.assertTrue(
                String.valueOf(req.get("ROLES_JSON")).contains("ROLE_FINANCE_MGR"));
        // 审批实例为 PermRequest（风险路由链，非原单节点 RoleGrantException）
        String bizType = jdbc.queryForObject(
                "SELECT BIZ_TYPE FROM erp_sys_approval WHERE BIZ_ID=?", String.class, requestId);
        org.junit.jupiter.api.Assertions.assertEquals("PermRequest", bizType);
        String instStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE BIZ_ID=?", String.class, requestId);
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", instStatus);
    }

    @Test
    void duplicatePendingSubmissionBlocked() throws Exception {
        createException("首次提交例外申请理由");
        mockMvc.perform(put(ACCOUNTS + "/" + target + "/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[\"" + plannerRoleId + "\",\"" + finMgrRoleId
                                + "\"],\"reason\":\"重复提交例外申请理由\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    void approvalAppliesTargetRolesWithLedgerAndAudit() throws Exception {
        String requestId = createException("月末对账需要该权限支持");
        String taskId = taskOf(requestId);

        // 链 = SEQ1 ROLE_DEPT_HEAD 回落节点（目标无汇报线）+ SEQ2 特权安全管理员会签
        //（FIN_MGR ∈ 特权清单）；两名签署人均非申请人本人
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(approver, null,
                        List.of(new SimpleGrantedAuthority("ROLE_DEPT_HEAD"))));
        approvalEngine.pass(taskId, "同意特批");
        // 第二节点：安全管理员会签（同一非申请人账号持 ADMIN 亦可签）
        String secondTask = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                        + "ON i.ID=t.APPR_ID WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE' "
                        + "ORDER BY t.SEQ LIMIT 1", String.class, requestId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(approver, null,
                        List.of(new SimpleGrantedAuthority("ROLE_DEPT_HEAD"),
                                new SimpleGrantedAuthority("ROLE_ADMIN"))));
        approvalEngine.pass(secondTask, "会签同意");
        SecurityContextHolder.clearContext();

        org.junit.jupiter.api.Assertions.assertTrue(userHasRole(target, "ROLE_PLANNER"));
        org.junit.jupiter.api.Assertions.assertTrue(userHasRole(target, "ROLE_FINANCE_MGR"),
                "审批通过后超基线角色须生效");
        // 台账来源 EXCEPTION + 12 个月到期
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT SOURCE, STATUS FROM erp_admin_grant_ledger WHERE APPLY_ID=? "
                        + "AND ROLE_CODE='ROLE_FINANCE_MGR'", requestId);
        org.junit.jupiter.api.Assertions.assertEquals("EXCEPTION", ledger.get("SOURCE"));
        org.junit.jupiter.api.Assertions.assertEquals("ACTIVE", ledger.get("STATUS"));
        // 申请单生效回填
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?", String.class, requestId);
        org.junit.jupiter.api.Assertions.assertEquals("ACTIVE", st);
        // 审计快照（新口径 PERMISSION_GRANTED）+ 审批意见留痕
        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? "
                        + "AND FIELD_NAME='PERMISSION_GRANTED'", Integer.class, target);
        org.junit.jupiter.api.Assertions.assertEquals(1, audit, "审计快照须落库");
        String opinion = jdbc.queryForObject(
                "SELECT OPINION FROM erp_sys_approval_task WHERE ID=?", String.class, taskId);
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(opinion).contains("同意"),
                "审批意见留痕");
    }

    @Test
    void rejectionKeepsRolesUnchanged() throws Exception {
        String requestId = createException("编制未批准先不授予该权限");
        String taskId = taskOf(requestId);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(approver, null,
                        List.of(new SimpleGrantedAuthority("ROLE_DEPT_HEAD"))));
        approvalEngine.reject(taskId, "编制未批准驳回");
        SecurityContextHolder.clearContext();

        org.junit.jupiter.api.Assertions.assertFalse(userHasRole(target, "ROLE_FINANCE_MGR"),
                "驳回后角色不得变化");
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?", String.class, requestId);
        org.junit.jupiter.api.Assertions.assertEquals("REJECTED", st);
        Integer ledger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_grant_ledger WHERE APPLY_ID=?",
                Integer.class, requestId);
        org.junit.jupiter.api.Assertions.assertEquals(0, ledger, "驳回不产生台账");
    }
}
