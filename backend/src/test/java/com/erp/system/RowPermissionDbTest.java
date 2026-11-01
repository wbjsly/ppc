package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.bi.RowPermissionService;
import com.erp.service.bi.QueryGovernance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级权限库测（add-perm-control-console，spec bi-row-permission）：
 * 三维度规则 / 多角色并集 BR-4.10-16 / 冲突取严 / SELF_SUBTREE 树展开 /
 * 非法枚举 422 / 异常回退默认拒绝 BR-4.10-17 / 影响预览。
 */
@SpringBootTest
class RowPermissionDbTest {

    @Autowired
    private RowPermissionService service;
    @Autowired
    private QueryGovernance queryGovernance;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    /** 本测打标前缀 */
    private static final String PFX = "rp_";

    @BeforeEach
    void setUp() {
        purge();
        seedOrgs();
        SystemAuth.login("rp-designer", "ROLE_BI_DESIGNER");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='BiRowPerm'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='BiRowPerm'");
        jdbc.update("DELETE v FROM erp_bi_perm_rule_ver v JOIN erp_bi_perm_rule r "
                + "ON v.RULE_ID=r.ID WHERE r.USER_NAME LIKE 'rp\\_%' OR r.ROLE_CODE LIKE 'rp\\_%'");
        jdbc.update("DELETE FROM erp_bi_perm_rule WHERE USER_NAME LIKE 'rp\\_%' "
                + "OR ROLE_CODE LIKE 'rp\\_%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='BiRowPermFallback'");
        // hr_change_log 有禁删触发器（BR-5.6-13），安全日志不清理，断言按规则 ID 精确匹配
        // 测试组织树
        jdbc.update("DELETE FROM erp_mdm_org_unit WHERE OU_CODE LIKE 'RPRO%'");
    }

    /** 造组织树：rp-root（LE-RP-01）→ rp-child（LE-RP-01）；rp-other（LE-RP-02） */
    private void seedOrgs() {
        jdbc.update("INSERT IGNORE INTO erp_mdm_org_unit (ID, OU_CODE, OU_NAME, LEGAL_ENTITY_ID, "
                        + "PARENT_ID, TREE_LEVEL, OU_TYPE, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('rp-org-root','RPRO-ROOT','RP测试根','LE-RP-01','',1,'DEPT','1','rptest',NOW(),'0',0)");
        jdbc.update("INSERT IGNORE INTO erp_mdm_org_unit (ID, OU_CODE, OU_NAME, LEGAL_ENTITY_ID, "
                        + "PARENT_ID, TREE_LEVEL, OU_TYPE, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('rp-org-child','RPRO-CHILD','RP测试子','LE-RP-01','rp-org-root',2,'DEPT','1','rptest',NOW(),'0',0)");
        jdbc.update("INSERT IGNORE INTO erp_mdm_org_unit (ID, OU_CODE, OU_NAME, LEGAL_ENTITY_ID, "
                        + "PARENT_ID, TREE_LEVEL, OU_TYPE, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('rp-org-other','RPRO-OTHER','RP其他','LE-RP-02','',1,'DEPT','1','rptest',NOW(),'0',0)");
    }

    /** 为测试用户造角色绑定 */
    private void grantRole(String user, String role) {
        jdbc.update("INSERT IGNORE INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "SELECT ?, ?, PASSWORD, ?, '1', 'rptest', NOW(), '0', 0 FROM erp_admin_user "
                        + "WHERE USERNAME='admin'", "ru-" + user, user, user);
        jdbc.update("INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, STATUS, SORT_ORDER, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, '1', 99, 'rptest', NOW(), '0', 0)",
                "rr-" + role, role, role);
        jdbc.update("INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) "
                        + "VALUES (?, ?, ?)", "rur-" + user + "-" + role, "ru-" + user, "rr-" + role);
    }

    // ---------- 三维度规则（spec 需求一） ----------

    @Test
    void createAndApprovalLifecycle() {
        Map<String, Object> p = new HashMap<>();
        p.put("userName", "rp_u1");
        p.put("scopeExpr", "SELF_SUBTREE");
        p.put("orgId", "rp-org-root");
        p.put("action", "PASS");
        String id = (String) service.create(p).get("id");
        assertEquals("DRAFT", statusOf(id), "创建为草稿");
        // 未审批不生效
        assertEquals(List.of(), queryGovernance.allowedEntities("rp_u1"), "DRAFT 不参与计算");

        Map<String, Object> sub = service.submit(id);
        assertEquals("PENDING_APPROVAL", statusOf(id), "提交后审批中");
        approve((String) sub.get("approvalInstanceId"));
        assertEquals("ENABLED", statusOf(id), "审批通过启用");
        // 树展开：root+child 同属 LE-RP-01
        List<String> allowed = queryGovernance.allowedEntities("rp_u1");
        assertEquals(List.of("LE-RP-01"), allowed, "SELF_SUBTREE 展开到法人主体");
        // 版本快照存在
        assertTrue(service.versions(id).size() >= 1, "生效生成版本快照");
    }

    @Test
    void invalidScopeExprRejected() {
        Map<String, Object> p = new HashMap<>();
        p.put("userName", "rp_u2");
        p.put("scopeExpr", "DROP TABLE x");
        p.put("action", "PASS");
        ServiceException e = assertThrows(ServiceException.class, () -> service.create(p));
        assertEquals(422, e.getCode(), "非法枚举 422（不接受自由表达式）");

        p.put("scopeExpr", "LIST");
        p.put("scopeList", "[]");
        e = assertThrows(ServiceException.class, () -> service.create(p));
        assertEquals(422, e.getCode(), "LIST 空清单 422");
    }

    // ---------- 多角色并集与冲突取严（spec 需求二，BR-4.10-16） ----------

    @Test
    void multiRoleUnionAndStricterWins() {
        grantRole("rp_union", "rp_ROLE_A");
        grantRole("rp_union", "rp_ROLE_B");
        // 角色 A：LE-RP-01 PASS；角色 B：LE-RP-02 PASS
        String a = createForRole("rp_ROLE_A", "SELF", "LE-RP-01", "PASS");
        submitAndApprove(a);
        String b = createForRole("rp_ROLE_B", "SELF", "LE-RP-02", "PASS");
        submitAndApprove(b);
        List<String> allowed = queryGovernance.allowedEntities("rp_union");
        assertTrue(allowed.contains("LE-RP-01") && allowed.contains("LE-RP-02"),
                "多角色并集（BR-4.10-16）：" + allowed);

        // 冲突取严：新增角色 B 对 LE-RP-01 的 DENY
        String deny = createForRole("rp_ROLE_B", "SELF", "LE-RP-01", "DENY");
        submitAndApprove(deny);
        allowed = queryGovernance.allowedEntities("rp_union");
        assertEquals(List.of("LE-RP-02"), allowed, "DENY 严于 PASS（BR-4.10-16）");
    }

    @Test
    void impactPreviewShowsSources() {
        grantRole("rp_prev", "rp_ROLE_P");
        String a = createForRole("rp_ROLE_P", "SELF", "LE-RP-01", "PASS");
        submitAndApprove(a);
        Map<String, Object> prev = service.impactPreview("rp_prev");
        assertEquals(Boolean.FALSE, prev.get("allEntities"), "非全量");
        @SuppressWarnings("unchecked")
        Map<String, String> sources = (Map<String, String>) prev.get("sources");
        assertTrue(sources.get("LE-RP-01").contains("role:rp_ROLE_P"),
                "生效规则来源可追溯：" + sources);
    }

    // ---------- 异常回退与默认拒绝（spec 需求四，BR-4.10-17） ----------

    @Test
    void brokenRuleFallsBackAndAlerts() {
        // 建规则并生效（V1 快照）
        Map<String, Object> p = new HashMap<>();
        p.put("userName", "rp_fb");
        p.put("scopeExpr", "SELF_SUBTREE");
        p.put("orgId", "rp-org-root");
        String id = (String) service.create(p).get("id");
        submitAndApprove(id);
        assertEquals(List.of("LE-RP-01"), queryGovernance.allowedEntities("rp_fb"), "正常展开");

        // 破坏组织节点（模拟 LIST/树节点失效）→ 当前规则展开失败
        jdbc.update("UPDATE erp_mdm_org_unit SET DEL_FLAG='1' WHERE ID='rp-org-root'");
        try {
            // 回退到 V1 快照重算：快照同为 SELF_SUBTREE(rp-org-root)，同样失效 → 默认拒绝空集
            List<String> after = queryGovernance.allowedEntities("rp_fb");
            assertEquals(List.of(), after, "展开失败默认拒绝（最小权限）");
            // 告警 + 安全日志
            Integer alert = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='BiRowPermFallback'",
                    Integer.class);
            assertTrue(alert >= 1, "告警推送 ROLE_DATA_GOV");
            Integer secLog = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hr_change_log WHERE FIELD_NAME='BI_ROW_RULE_FALLBACK' "
                            + "AND EMP_ID=?", Integer.class, id);
            assertTrue(secLog >= 1, "安全日志留痕（按规则 ID 精确匹配）");
        } finally {
            jdbc.update("UPDATE erp_mdm_org_unit SET DEL_FLAG='0' WHERE ID='rp-org-root'");
        }
    }

    @Test
    void wildcardCompatAndLegacySemantics() {
        // 存量语义兼容：SCOPE_EXPR=NULL + ENTITY_ID='*' → 全量（null）
        jdbc.update("INSERT INTO erp_bi_perm_rule (ID, RULE_TYPE, TARGET_KEY, USER_NAME, ENTITY_ID, "
                        + "ACTION, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, 'ROW_LE', '*', ?, '*', 'PASS', 'ENABLED', 'rptest', NOW(), '0', 0)",
                uuid(), "rp_legacy");
        assertNull(queryGovernance.allowedEntities("rp_legacy"), "存量通配语义 = null 全量");
        // 无授权用户 → 空集默认拒绝
        assertEquals(List.of(), queryGovernance.allowedEntities("rp_nobody"), "无授权默认拒绝");
    }

    // ---------- helpers ----------

    private String createForRole(String role, String expr, String entityId, String action) {
        Map<String, Object> p = new HashMap<>();
        p.put("roleCode", role);
        p.put("scopeExpr", expr);
        p.put("entityId", entityId);
        p.put("action", action);
        return (String) service.create(p).get("id");
    }

    private void submitAndApprove(String id) {
        Map<String, Object> sub = service.submit(id);
        approve((String) sub.get("approvalInstanceId"));
    }

    /** 审批链签署：签署人 ≠ 发起人（C-0-03 禁自签） */
    private void approve(String instId) {
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1 "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1",
                String.class, instId);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "rp-signer", null,
                        List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_DATA_GOV"))));
        try {
            approvalEngine.pass(taskId, "行级权限审核通过");
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("rp-designer", "ROLE_BI_DESIGNER");
        }
    }

    private String statusOf(String id) {
        return jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_perm_rule WHERE ID=?", String.class, id);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
