package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.bi.MaskRuleService;
import com.erp.service.bi.QueryGovernance;
import com.erp.service.bi.TempGrantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 临时授权库测（add-perm-control-console，spec bi-temp-grant）：
 * 申请审批放行明文 / 超 24h 422 / 到期自动收回 / 驳回不生效 / 不扩行级范围。
 */
@SpringBootTest
class TempGrantDbTest {

    @Autowired
    private TempGrantService service;
    @Autowired
    private MaskRuleService maskRuleService;
    @Autowired
    private QueryGovernance queryGovernance;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SystemAuth.login("tg-designer", "ROLE_BI_DESIGNER");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='BiTempGrant'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='BiTempGrant'");
        jdbc.update("DELETE FROM erp_bi_temp_grant WHERE FIELD_KEY LIKE 'tg\\_%'");
        jdbc.update("DELETE FROM erp_bi_perm_rule WHERE TARGET_KEY LIKE 'tg\\_%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='BiTempGrantRejected'");
    }

    /** 建一条 tg_salary 掩码规则并生效 */
    private void seedMaskRule() {
        Map<String, Object> p = new HashMap<>();
        p.put("targetKey", "tg_salary");
        p.put("maskStyle", "SALARY");
        String id = (String) maskRuleService.create(p).get("id");
        Map<String, Object> sub = maskRuleService.submit(id);
        approve((String) sub.get("approvalInstanceId"), "MaskRule");
    }

    // ---------- 申请审批与明文放行（spec 需求一） ----------

    @Test
    void applyApproveReleasesPlaintext() {
        seedMaskRule();
        // 无授权 → 掩码
        SystemAuth.login("tg-user", "ROLE_BI_DESIGNER");
        assertEquals("***", queryGovernance.maskIfHit("tg_salary", "25000"), "无授权掩码");

        Map<String, Object> p = new HashMap<>();
        p.put("fieldKey", "tg_salary");
        p.put("purpose", "薪酬结构分析用途");
        p.put("hours", 4);
        Map<String, Object> apply = service.apply(p);
        String id = (String) apply.get("id");
        assertEquals("PENDING_APPROVAL", apply.get("status"), "申请即入审批");

        approve((String) apply.get("approvalInstanceId"), "BiTempGrant");
        assertEquals("ACTIVE", statusOf(id), "审批通过生效");
        // approve() 结束时登录态被还原，重新登录为授权用户再验明文
        SystemAuth.login("tg-user", "ROLE_BI_DESIGNER");
        assertEquals("25000", queryGovernance.maskIfHit("tg_salary", "25000"),
                "有效临时授权放行明文");
    }

    @Test
    void overCapHoursRejected() {
        Map<String, Object> p = new HashMap<>();
        p.put("fieldKey", "tg_salary");
        p.put("purpose", "超上限测试用途");
        p.put("hours", 48);
        ServiceException e = assertThrows(ServiceException.class, () -> service.apply(p));
        assertEquals(422, e.getCode(), "超 24h 上限 422（spec）");
    }

    // ---------- 到期自动收回（spec 需求二） ----------

    @Test
    void expiredGrantAutoRecalled() {
        seedMaskRule();
        SystemAuth.login("tg-user2", "ROLE_BI_DESIGNER");
        Map<String, Object> p = new HashMap<>();
        p.put("fieldKey", "tg_salary");
        p.put("purpose", "到期收回测试用途");
        p.put("hours", 1);
        Map<String, Object> apply = service.apply(p);
        String id = (String) apply.get("id");
        approve((String) apply.get("approvalInstanceId"), "BiTempGrant");
        SystemAuth.login("tg-user2", "ROLE_BI_DESIGNER");
        assertEquals("25000", queryGovernance.maskIfHit("tg_salary", "25000"), "生效期明文");

        // 回拨过期时间模拟到期
        jdbc.update("UPDATE erp_bi_temp_grant SET EXPIRE_AT=? WHERE ID=?",
                LocalDateTime.now().minusMinutes(1), id);
        int n = service.expireOverdue();
        assertTrue(n >= 1, "扫描回收过期授权");
        assertEquals("EXPIRED", statusOf(id), "状态置 EXPIRED");
        assertEquals("***", queryGovernance.maskIfHit("tg_salary", "25000"),
                "过期后恢复掩码");
    }

    // ---------- 驳回不生效（spec 需求一） ----------

    @Test
    void rejectedGrantStaysMasked() {
        seedMaskRule();
        SystemAuth.login("tg-user3", "ROLE_BI_DESIGNER");
        Map<String, Object> p = new HashMap<>();
        p.put("fieldKey", "tg_salary");
        p.put("purpose", "驳回场景测试用途");
        Map<String, Object> apply = service.apply(p);
        String id = (String) apply.get("id");
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1 "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1",
                String.class, (String) apply.get("approvalInstanceId"));
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "tg-signer", null,
                        List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_DATA_GOV"))));
        try {
            approvalEngine.reject(taskId, "用途不充分");
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("tg-user3", "ROLE_BI_DESIGNER");
        }
        assertEquals("REJECTED", statusOf(id), "驳回置 REJECTED");
        assertEquals("***", queryGovernance.maskIfHit("tg_salary", "25000"), "驳回保持掩码");
    }

    // ---------- helpers ----------

    /** 签署（signer ≠ 申请人，C-0-03 禁自签） */
    private void approve(String instId, String bizType) {
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1 "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1",
                String.class, instId);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "tg-signer", null,
                        List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_DATA_GOV"))));
        try {
            approvalEngine.pass(taskId, bizType + " 审核通过");
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("tg-designer", "ROLE_BI_DESIGNER");
        }
    }

    private String statusOf(String id) {
        return jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_temp_grant WHERE ID=?", String.class, id);
    }
}
