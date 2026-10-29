package com.erp.approval;

import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按人节点与禁自签（add-permission-approval-flow spec approval-workflow MODIFIED）：
 * 按人节点仅指定人可签；PermRequest 发起人自签 403 + 违规留痕；存量角色节点行为不变。
 */
@SpringBootTest
class PersonNodeDbTest {

    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void purge() {
        jdbc.execute("DELETE t FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval i ON t.APPR_ID = i.ID WHERE i.BIZ_ID LIKE 'pn-%'");
        jdbc.execute("DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'pn-%'");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE FIELD_NAME = 'SOD_SELF_SIGN_VIOLATION'; SET @audit_bypass=0");
        SecurityContextHolder.clearContext();
    }

    private void login(String userId, String... roles) {
        var authorities = java.util.stream.Stream.of(roles)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, authorities));
    }

    // ---------- 按人节点 ----------

    @Test
    void personNodeOnlyDesignatedSignerCanPass() {
        login("pn-creator", "ROLE_ADMIN");
        var inst = engine.submit("PermRequest", "pn-1", "按人节点测试", null,
                List.of(List.of(ApprovalNodeSpec.person("pn-mgr-id", "直属主管"))));
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1",
                String.class, inst.getId());
        // 指定签署人预置在 SIGNER
        String designated = jdbc.queryForObject(
                "SELECT SIGNER FROM erp_sys_approval_task WHERE ID=?", String.class, taskId);
        assertEquals("pn-mgr-id", designated, "按人节点提交即预置指定签署人");

        // 他人（即使持 ADMIN 角色）→ 403
        login("pn-other", "ROLE_ADMIN");
        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.pass(taskId, "同意"));
        assertEquals(403, e.getCode(), "非指定人签署按人节点应 403");

        // 指定人本人（无需角色）→ 通过
        login("pn-mgr-id");
        var after = engine.pass(taskId, "同意");
        assertEquals("APPROVED", after.getStatus());
    }

    @Test
    void personNodeFactoryRequiresSigner() {
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalNodeSpec.person(null, "主管"));
        assertThrows(IllegalArgumentException.class,
                () -> ApprovalNodeSpec.person("  ", "主管"));
        ApprovalNodeSpec spec = ApprovalNodeSpec.person("u-1", "主管");
        assertTrue(spec.isPersonNode());
        assertTrue(spec.getRoleRequired() == null || spec.getRoleRequired().isBlank());
    }

    // ---------- 禁自签（BR-5.6-01，仅 PermRequest 白名单） ----------

    @Test
    void permRequestApplicantSelfSignBlockedWithAudit() {
        // 申请人持 ROLE_PM，链为角色节点 ROLE_PM → 自签应 403 并留痕
        login("pn-applicant", "ROLE_PM");
        var inst = engine.submit("PermRequest", "pn-2", "禁自签测试", null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PM", "主管"))));
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1",
                String.class, inst.getId());

        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.pass(taskId, "同意"));
        assertEquals(403, e.getCode(), "PermRequest 发起人自签应 403");
        Integer violations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE FIELD_NAME='SOD_SELF_SIGN_VIOLATION' "
                        + "AND EMP_ID='pn-applicant'", Integer.class);
        assertEquals(1, violations, "职责分离违规日志须落库");
        // 节点未被签署
        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval_task WHERE ID=?", String.class, taskId);
        assertEquals("ACTIVE", status);

        // 另一人（同角色）可签 → 实例通过
        login("pn-approver", "ROLE_PM");
        var after = engine.pass(taskId, "同意");
        assertEquals("APPROVED", after.getStatus());
    }

    @Test
    void nonWhitelistedBizTypeSelfSignUnchanged() {
        // 存量 bizType 不受禁自签影响（范围化证据）
        login("pn-solo", "ROLE_PM");
        var inst = engine.submit("SomeLegacyBiz", "pn-3", "存量自签回归", null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PM", "主管"))));
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1",
                String.class, inst.getId());
        var after = engine.pass(taskId, "同意"); // 同人自签放行（原行为）
        assertEquals("APPROVED", after.getStatus());
    }

    @Test
    void roleNodeBehaviorUnchanged() {
        // 角色节点：无角色者 403、有角色者通过（原逻辑回归）
        login("pn-r1", "ROLE_SALES");
        var inst = engine.submit("PermRequest", "pn-4", "角色节点回归", null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PM", "主管"))));
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1",
                String.class, inst.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.pass(taskId, "同意"));
        assertEquals(403, e.getCode());

        login("pn-r2", "ROLE_PM");
        assertNotNull(engine.pass(taskId, "同意"));
    }

    @Test
    void duplicateSubmitStillRejected() {
        login("pn-dup", "ROLE_ADMIN");
        engine.submit("PermRequest", "pn-5", "重复提交", null,
                List.of(List.of(ApprovalNodeSpec.person("pn-x", "主管"))));
        ServiceException e = assertThrows(ServiceException.class,
                () -> engine.submit("PermRequest", "pn-5", "重复提交", null,
                        List.of(List.of(ApprovalNodeSpec.person("pn-x", "主管")))));
        assertEquals(422, e.getCode());
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
