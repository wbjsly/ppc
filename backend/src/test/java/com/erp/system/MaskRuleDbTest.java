package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.bi.MaskRuleService;
import com.erp.service.bi.QueryGovernance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏规则库测（add-data-extract-console，spec bi-mask-rule-management）：
 * 类型化样式输出 / 角色差异化 / 多命中取最严 / 审批激活（未审批不生效、驳回退回）/
 * 变更留痕 / 未命中默认 GENERIC（C-4.10-02）。
 */
@SpringBootTest
class MaskRuleDbTest {

    @Autowired
    private MaskRuleService service;
    @Autowired
    private QueryGovernance queryGovernance;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SystemAuth.login("mq-designer", "ROLE_BI_DESIGNER");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='MaskRule'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='MaskRule'");
        // 造数打标：本测规则 targetKey 统一 mq_ 前缀；通配 * 规则按创建人打标清理
        jdbc.update("DELETE FROM erp_bi_mask_rule_log WHERE RULE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_bi_perm_rule WHERE TARGET_KEY LIKE 'mq_%' "
                + "OR (TARGET_KEY='*' AND CREATE_BY='mq-designer')) t)");
        jdbc.update("DELETE FROM erp_bi_perm_rule WHERE TARGET_KEY LIKE 'mq_%' "
                + "OR (TARGET_KEY='*' AND CREATE_BY='mq-designer')");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='MaskRuleApproval'");
    }

    private String create(String targetKey, String style, String roleCode) {
        Map<String, Object> p = new HashMap<>();
        p.put("targetKey", targetKey);
        p.put("ruleType", "COL_MASK");
        p.put("maskStyle", style);
        p.put("roleCode", roleCode);
        return (String) service.create(p).get("id");
    }

    /** 审批链：发起人 mq-designer，签署人 signer-1 + ROLE_DATA_GOV（≠ 发起人） */
    private void approve(String instId) {
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1 "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1",
                String.class, instId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("signer-1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_DATA_GOV"))));
        try {
            approvalEngine.pass(taskId, "脱敏合规审核通过");
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("mq-designer", "ROLE_BI_DESIGNER");
        }
    }

    // ---------- 样式输出（spec 需求一） ----------

    @Test
    void typedStylesApplyAfterApproval() {
        // PHONE：值 13856781234 → 138****1234（前3后4）
        String phoneId = create("mq_phone", "PHONE", null);
        // 未审批（DRAFT）→ 不生效（明文原样）
        assertEquals("13856781234", queryGovernance.maskIfHit("mq_phone", "13856781234"),
                "DRAFT 不参与掩码判定");
        Map<String, Object> sub = service.submit(phoneId);
        approve((String) sub.get("approvalInstanceId"));
        assertEquals("ENABLED", statusOf(phoneId), "审批通过启用");
        assertEquals("138****1234", queryGovernance.maskIfHit("mq_phone", "13856781234"),
                "PHONE 前3后4");

        // SALARY：固定掩码
        String salaryId = create("mq_salary", "SALARY", null);
        submitAndApprove(salaryId);
        assertEquals("***", queryGovernance.maskIfHit("mq_salary", "25000"), "SALARY 全掩码");

        // BANK：保留后4位
        String bankId = create("mq_bank", "BANK", null);
        submitAndApprove(bankId);
        assertEquals("****6789", queryGovernance.maskIfHit("mq_bank", "123456786789"), "BANK 尾4位");

        // ID_CARD：前3后4
        String idcId = create("mq_idcard", "ID_CARD", null);
        submitAndApprove(idcId);
        assertEquals("110****1234",
                queryGovernance.maskIfHit("mq_idcard", "110101199001011234"), "ID_CARD 前3后4");
    }

    // ---------- 角色差异化（spec 需求三） ----------

    @Test
    void roleDifferentiatedMasking() {
        String id = create("mq_role_s", "SALARY", "ROLE_DEPT_MANAGER");
        submitAndApprove(id);
        // 持有角色 → 掩码
        SystemAuth.login("mgr-user", "ROLE_DEPT_MANAGER");
        assertEquals("***", queryGovernance.maskIfHit("mq_role_s", "20000"), "部门经理掩码");
        // 无该角色且无其他规则 → 明文
        SystemAuth.login("director-user", "ROLE_BI_DIRECTOR");
        assertEquals("20000", queryGovernance.maskIfHit("mq_role_s", "20000"), "非适用角色明文");
    }

    // ---------- 多命中取最严（spec 需求二） ----------

    @Test
    void strictestStyleWins() {
        // 通配 GENERIC（全体）+ 字段级 PHONE（全体）→ PHONE 更严且字段级优先
        String globId = create("*", "GENERIC", null);
        submitAndApprove(globId);
        String phoneId = create("mq_multi", "PHONE", null);
        submitAndApprove(phoneId);
        assertEquals("138****1234", queryGovernance.maskIfHit("mq_multi", "13856781234"),
                "字段级 PHONE 优先于通配 GENERIC");
        // 其他字段仍按通配 GENERIC
        String other = String.valueOf(queryGovernance.maskIfHit("mq_other_col", "abcdefg"));
        assertEquals("a***g", other, "通配 GENERIC 首尾保留");
        // 同级更严：SALARY 覆盖 PHONE
        String salaryId = create("mq_multi", "SALARY", null);
        submitAndApprove(salaryId);
        assertEquals("***", queryGovernance.maskIfHit("mq_multi", "13856781234"),
                "SALARY 严于 PHONE");
    }

    // ---------- 审批与驳回（spec 需求三） ----------

    @Test
    void rejectReturnsToDraft() {
        String id = create("mq_rej", "PHONE", null);
        Map<String, Object> sub = service.submit(id);
        String instId = (String) sub.get("approvalInstanceId");
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1 "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1",
                String.class, instId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("signer-1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_DATA_GOV"))));
        try {
            approvalEngine.reject(taskId, "场景需复核");
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("mq-designer", "ROLE_BI_DESIGNER");
        }
        assertEquals("DRAFT", statusOf(id), "驳回退回草稿");
        // 留痕含 REJECT
        Integer rej = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_mask_rule_log WHERE RULE_ID=? AND ACTION='REJECT'",
                Integer.class, id);
        assertTrue(rej >= 1, "驳回留痕");
        // 通知已广播
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MaskRuleApproval'",
                Integer.class);
        assertTrue(n >= 1, "驳回通知");
    }

    @Test
    void enabledRuleNotEditable() {
        String id = create("mq_locked", "GENERIC", null);
        submitAndApprove(id);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.update(id, Map.of("maskStyle", "PHONE")));
        assertEquals(422, e.getCode(), "已生效不可改：" + e.getMessage());
        // 停用即时生效且留痕
        service.disable(id);
        assertEquals("DISABLED", statusOf(id));
        Integer dis = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_mask_rule_log WHERE RULE_ID=? AND ACTION='DISABLE'",
                Integer.class, id);
        assertTrue(dis >= 1, "停用留痕");
    }

    // ---------- 未命中默认掩码（C-4.10-02） ----------

    @Test
    void sensitiveColumnDefaultsToGenericMask() {
        // COL_SENSITIVE 登记但无掩码规则命中 → GENERIC
        Map<String, Object> p = new HashMap<>();
        p.put("targetKey", "mq_sensitive");
        p.put("ruleType", "COL_SENSITIVE");
        String id = (String) service.create(p).get("id");
        submitAndApprove(id);
        assertEquals("a***g", String.valueOf(queryGovernance.maskIfHit("mq_sensitive", "abcdefg")),
                "敏感未命中默认 GENERIC 掩码");
    }

    // ---------- helpers ----------

    private void submitAndApprove(String id) {
        Map<String, Object> sub = service.submit(id);
        approve((String) sub.get("approvalInstanceId"));
    }

    private String statusOf(String id) {
        return jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_perm_rule WHERE ID=?", String.class, id);
    }
}
