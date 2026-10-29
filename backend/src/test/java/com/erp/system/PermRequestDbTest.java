package com.erp.system;

import com.erp.service.PermRequestService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限申请链与回调（add-permission-approval-flow spec：风险分级审批链与自批阻断 /
 * 例外统一入口）：三档链形态、主管自申请上移、SoD 合规官、通过生效、驳回不动、例外全集替换。
 */
@SpringBootTest
class PermRequestDbTest {

    @Autowired
    private PermRequestService service;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    private String applicant;
    private String manager;
    private String topManager;
    private String target;

    @BeforeEach
    void setUp() {
        purge();
        // 链形态断言基于窗口内提交（add-change-window-console：窗口外普通/敏感单会追加 CCB 节点）
        seedAlwaysWindow();
        applicant = seedUser("pdb-app");
        manager = seedUser("pdb-mgr");
        topManager = seedUser("pdb-top");
        target = seedUser("pdb-tgt");
        // 汇报链：target → manager → topManager（top 无上级）
        String empTop = seedEmployee("PDBTOP", topManager, null);
        seedEmployee("PDBMGR", manager, empTop);
        seedEmployee("PDBTGT", target, empByUser(manager));
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.execute("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='PermRequest' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'pdb-%') u)) r)");
        jdbc.execute("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='PermRequest' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'pdb-%') u)) r)");
        jdbc.execute("DELETE FROM erp_admin_grant_ledger WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'pdb-%') t)");
        jdbc.execute("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'pdb-%'");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'pdb-%') t); SET @audit_bypass=0");
        jdbc.execute("DELETE FROM erp_sys_notice WHERE TARGET_USER IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'pdb-%') t) "
                + "OR BIZ_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_perm_request "
                + "WHERE TARGET_USER_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'pdb-%') u)) r)");
        jdbc.execute("DELETE FROM erp_admin_perm_request WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'pdb-%') t)");
        jdbc.execute("DELETE FROM hr_employee WHERE EMP_NO LIKE 'PDB%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'pdb-%'");
        jdbc.execute("DELETE FROM erp_change_window WHERE CREATE_BY = 'pdb-test'");
        SecurityContextHolder.clearContext();
    }

    /** 全周全天窗口（判定恒为窗口内，隔离外部窗口状态） */
    private void seedAlwaysWindow() {
        for (int d = 1; d <= 7; d++) {
            jdbc.update("INSERT INTO erp_change_window (ID,NAME,DAY_OF_WEEK,START_TIME,END_TIME,"
                            + "STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES "
                            + "(?,?,?, '00:00', '23:59:59', '1', 'pdb-test', NOW(), '0', 0)",
                    "pdb-win-" + d, "链测试窗口-" + d, d);
        }
    }

    private String seedUser(String name) {
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','pdb-test','0',0)",
                id, name, "{noop}", "权限链测试");
        return id;
    }

    private String empByUser(String userId) {
        return jdbc.queryForObject(
                "SELECT ID FROM hr_employee WHERE USER_ID=? AND DEL_FLAG='0'", String.class, userId);
    }

    private String seedEmployee(String empNo, String userId, String reportTo) {
        String id = "pdb-emp-" + empNo;
        jdbc.update("INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,EMP_STATUS,HIRE_DATE,"
                        + "USER_ID,REPORT_TO_EMP_ID,ATTEND_SCHEME,CREATE_BY,DEL_FLAG,VER_NO) "
                        + "VALUES (?,?,?,?,'在职',CURDATE(),?,?,'STD','pdb-test','0',0)",
                id, empNo, "权限链" + empNo, "PDBID-" + empNo, userId, reportTo);
        return id;
    }

    private void login(String userId, String... roles) {
        var authorities = java.util.stream.Stream.of(roles)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, authorities));
    }

    private Map<String, Object> submit(String... roleCodes) {
        return service.submitNormal(target, List.of(roleCodes), "华东/OU-01",
                "权限链测试申请理由足够长", null, 12, "IMMEDIATE", null);
    }

    private List<Map<String, Object>> tasksOf(String requestId) {
        return jdbc.queryForList(
                "SELECT t.SEQ AS seq, t.NODE_TYPE AS nodeType, t.ROLE_REQUIRED AS roleRequired, "
                        + "t.SIGNER AS signer, t.STATUS AS status, t.ID AS id "
                        + "FROM erp_sys_approval_task t JOIN erp_sys_approval i ON i.ID=t.APPR_ID "
                        + "WHERE i.BIZ_ID=? ORDER BY t.SEQ", requestId);
    }

    @Test
    void normalSinglePersonNodeApprovalFlow() {
        login(applicant, "ROLE_ADMIN");
        Map<String, Object> out = submit("ROLE_PLANNER");
        String reqId = String.valueOf(out.get("id"));

        List<Map<String, Object>> tasks = tasksOf(reqId);
        assertEquals(1, tasks.size(), "普通单级链仅一个节点");
        assertEquals("SIGN", tasks.get(0).get("nodeType"));
        assertEquals(manager, tasks.get(0).get("signer"), "按人节点 = 直属主管");
        assertEquals(null, tasks.get(0).get("roleRequired"), "按人节点无角色要求");

        // 主管签署 → 生效（IMMEDIATE + 非特权）
        login(manager);
        engine.pass(String.valueOf(tasks.get(0).get("id")), "同意");

        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?", String.class, reqId);
        assertEquals("ACTIVE", status);
        Integer ledger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_grant_ledger WHERE APPLY_ID=?", Integer.class, reqId);
        assertEquals(1, ledger, "通过后落台账");
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r "
                        + "ON r.ID=ur.ROLE_ID WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_PLANNER'",
                Integer.class, target);
        assertEquals(1, bound, "角色投影写入");
    }

    @Test
    void sensitiveTwoLevelChain() {
        login(applicant, "ROLE_ADMIN");
        String reqId = String.valueOf(submit("ROLE_COST_ACCOUNTANT").get("id"));
        List<Map<String, Object>> tasks = tasksOf(reqId);
        assertEquals(2, tasks.size(), "敏感 = 主管 + 数据 Owner 两级");
        assertEquals(manager, tasks.get(0).get("signer"));
        assertEquals("ROLE_FINANCE_MGR", tasks.get(1).get("roleRequired"), "成本数据 Owner");
        assertEquals("SIGN", tasks.get(1).get("nodeType"));
    }

    @Test
    void privilegedThreeNodeChainWithJoint() {
        login(applicant, "ROLE_ADMIN");
        // 特权 + 敏感组合 → 主管 / 数据 Owner / 安全管理员会签 三节点
        Map<String, Object> out = service.submitNormal(target,
                List.of("ROLE_COST_ACCOUNTANT", "ROLE_ADMIN"), "华东/OU-01",
                "故障处置需要超管与成本权限", "INC-2026-1029-0077", 1, "IMMEDIATE", null);
        assertEquals("PRIVILEGED", out.get("riskLevel"));
        List<Map<String, Object>> tasks = tasksOf(String.valueOf(out.get("id")));
        assertEquals(3, tasks.size(), "特权三节点");
        assertEquals("ROLE_FINANCE_MGR", tasks.get(1).get("roleRequired"));
        assertEquals("JOINT", tasks.get(2).get("nodeType"), "安全管理员会签");
        assertEquals("ROLE_ADMIN", tasks.get(2).get("roleRequired"));
    }

    @Test
    void sodConflictAppendsComplianceNode() {
        login(applicant, "ROLE_ADMIN");
        // 注意：种子互斥对成员均含审批权（PURCHASER/PM ∈ 特权清单）→ 本单为特权级，
        // 链 = 按人主管 / 安全管理员会签 / SoD 合规官（合规官位于生效之前）
        Map<String, Object> out = service.submitNormal(target,
                List.of("ROLE_PURCHASER", "ROLE_PM"), "华东/OU-01",
                "采购与审批权限互斥预检测试理由", "INC-SOD-0001", 12, "IMMEDIATE", null);
        assertEquals("PRIVILEGED", out.get("riskLevel"), "含审批权角色 → 特权级");
        String reqId = String.valueOf(out.get("id"));
        List<Map<String, Object>> tasks = tasksOf(reqId);
        assertEquals(3, tasks.size(), "主管 + 安全管理员会签 + 合规官");
        assertEquals(manager, tasks.get(0).get("signer"));
        assertEquals("JOINT", tasks.get(1).get("nodeType"));
        assertEquals("ROLE_COMPLIANCE", tasks.get(2).get("roleRequired"),
                "SoD 命中追加合规官（BR-5.6-02）");
    }

    @Test
    void applicantIsManagerWalksUpOneLevel() {
        // 申请人恰为直属主管 → 按人节点上移至主管的主管（BR-5.6-01 构建层）
        login(manager, "ROLE_ADMIN");
        Map<String, Object> out = service.submitNormal(target, List.of("ROLE_PLANNER"),
                "华东/OU-01", "主管为下属申请权限的理由", null, 12, "IMMEDIATE", null);
        List<Map<String, Object>> tasks = tasksOf(String.valueOf(out.get("id")));
        assertEquals(topManager, tasks.get(0).get("signer"), "自批上移至上级主管");
    }

    @Test
    void exceptionApprovalReplacesProjectionAndLedgers() {
        // 目标现持有 ROLE_SALES；例外申请目标全集 [PLANNER] → 通过后 = 精确全集
        String salesRole = jdbc.queryForObject(
                "SELECT ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_SALES'", String.class);
        jdbc.update("INSERT INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) VALUES (?,?,?)",
                "pdb-ur-sales", target, salesRole);

        login(applicant, "ROLE_ADMIN");
        Map<String, Object> out = service.submitException(target, List.of("ROLE_PLANNER"),
                List.of("ROLE_PLANNER"), "超基线例外授予测试理由");
        assertEquals("EXCEPTION", out.get("kind"));
        String reqId = String.valueOf(out.get("id"));

        List<Map<String, Object>> tasks = tasksOf(reqId);
        login(manager);
        engine.pass(String.valueOf(tasks.get(0).get("id")), "同意");

        // 全集替换：SALES 被移除、PLANNER 生效、台账来源 EXCEPTION
        Integer sales = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r "
                        + "ON r.ID=ur.ROLE_ID WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_SALES'",
                Integer.class, target);
        assertEquals(0, sales, "例外通过后按目标全集替换");
        Integer planner = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r "
                        + "ON r.ID=ur.ROLE_ID WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_PLANNER'",
                Integer.class, target);
        assertEquals(1, planner);
        String source = jdbc.queryForObject(
                "SELECT SOURCE FROM erp_admin_grant_ledger WHERE APPLY_ID=?", String.class, reqId);
        assertEquals("EXCEPTION", source);
    }

    @Test
    void rejectionKeepsRolesUnchanged() {
        login(applicant, "ROLE_ADMIN");
        String reqId = String.valueOf(submit("ROLE_PLANNER").get("id"));
        List<Map<String, Object>> tasks = tasksOf(reqId);

        login(manager);
        engine.reject(String.valueOf(tasks.get(0).get("id")), "业务必要性不充分");

        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?", String.class, reqId);
        assertEquals("REJECTED", status);
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r "
                        + "ON r.ID=ur.ROLE_ID WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_PLANNER'",
                Integer.class, target);
        assertEquals(0, bound, "驳回不生效");
        Integer ledger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_grant_ledger WHERE APPLY_ID=?", Integer.class, reqId);
        assertEquals(0, ledger);
    }
}
