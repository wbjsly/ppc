package com.erp.crm;

import com.erp.common.ServiceException;
import com.erp.entity.crm.CrmAssignRule;
import com.erp.entity.crm.Lead;
import com.erp.service.crm.LeadAutoAssignService;
import com.erp.service.crm.LeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线索自动分配 DbTest（add-lead-auto-assign，spec crm-lead-management 等级自动分配路由）。
 * 造数前缀 laa-；销售账号 laa-sales-a/b/c 挂 ROLE_SALES。
 */
@SpringBootTest
class LeadAutoAssignDbTest {

    @Autowired
    private LeadAutoAssignService autoAssignService;
    @Autowired
    private LeadService leadService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        // 两个 ROLE_SALES 测试账号
        seedSales("a");
        seedSales("b");
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "junit-sales", null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_SALES"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='LEAD_AUTO_ASSIGN'");
        jdbc.update("DELETE FROM erp_crm_lead_pool WHERE LEAD_ID LIKE 'laa-lead-%'");
        jdbc.update("DELETE FROM erp_crm_lead WHERE ID LIKE 'laa-lead-%' OR LEAD_NO LIKE 'LAALAA%'");
        jdbc.update("DELETE FROM erp_crm_sales_profile WHERE EMP_ID LIKE 'laa-%'");
        jdbc.update("DELETE FROM erp_crm_assign_rule WHERE ID LIKE 'laar-%' OR TARGET_EMP_IDS LIKE 'laa-%'");
        jdbc.update("DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'laa-user-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID LIKE 'laa-user-%' OR USERNAME LIKE 'laa-%'");
    }

    private void seedSales(String s) {
        jdbc.update("INSERT INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'x', '测试销售" + s + "', '1', 'junit', '0', 0)",
                "laa-user-" + s, "laa-sales-" + s);
        jdbc.update("INSERT INTO erp_admin_user_role (ID, USER_ID, ROLE_ID) "
                + "SELECT ?, ?, ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_SALES'",
                "laa-ur-" + s, "laa-user-" + s);
    }

    private void markSenior(String account) {
        jdbc.update("INSERT INTO erp_crm_sales_profile (ID, EMP_ID, EMP_NAME, IS_SENIOR, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, '1', 'junit', '0', 0)",
                "laa-prof-" + account, account, account);
    }

    private Lead seedLead(String suffix, String grade, String region, String ownerId) {
        String id = "laa-lead-" + suffix;
        jdbc.update("INSERT INTO erp_crm_lead (ID, LEAD_NO, SOURCE_CHANNEL, COMPANY_NAME, CONTACT_NAME, "
                + "REGION, GRADE, OWNER_ID, STATUS, POOL_FLAG, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'WEB', ?, ?, ?, ?, ?, 'OPEN', '0', 'junit', '0', 0)",
                id, "LAALAA-" + suffix.toUpperCase(), "线索公司" + suffix, "联系人" + suffix,
                region, grade, ownerId);
        return jdbc.query("SELECT * FROM erp_crm_lead WHERE ID=?",
                rs -> {
                    rs.next();
                    Lead l = new Lead();
                    l.setId(rs.getString("ID"));
                    l.setLeadNo(rs.getString("LEAD_NO"));
                    l.setCompanyName(rs.getString("COMPANY_NAME"));
                    l.setGrade(rs.getString("GRADE"));
                    l.setRegion(rs.getString("REGION"));
                    l.setOwnerId(rs.getString("OWNER_ID"));
                    l.setPoolFlag(rs.getString("POOL_FLAG"));
                    l.setStatus(rs.getString("STATUS"));
                    l.setExpectAmount(null);
                    return l;
                }, id);
    }

    private String ownerOf(String leadId) {
        return jdbc.query("SELECT OWNER_ID FROM erp_crm_lead WHERE ID=?",
                rs -> rs.next() ? rs.getString(1) : null, leadId);
    }

    @Test
    void gradeARoutesToLeastBusySenior() {
        markSenior("laa-sales-a");
        markSenior("laa-sales-b");
        // a 已有 2 条在手，b 0 条 → A 级应给 b
        seedLead("busy1", "A", null, "laa-sales-a");
        seedLead("busy2", "A", null, "laa-sales-a");
        Lead lead = seedLead("a1", "A", "华东", null);
        Map<String, Object> out = autoAssignService.route(lead);
        assertEquals(true, out.get("assigned"));
        assertEquals("laa-sales-b", out.get("ownerId"), "最少在手资深优先：" + out);
        assertEquals("AUTO", out.get("source"));
        assertEquals("laa-sales-b", ownerOf(lead.getId()));
        // 站内通知落库（定向新负责人）
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='LEAD_AUTO_ASSIGN' "
                + "AND BIZ_ID=?", Integer.class, lead.getId());
        assertTrue(n >= 1, "通知落库");
    }

    @Test
    void seniorEmptyFallsBackWithWarning() {
        // 资深名单为空：A 级回落全员最少在手 + warning
        Lead lead = seedLead("a2", "A", null, null);
        Map<String, Object> out = autoAssignService.route(lead);
        assertEquals(true, out.get("assigned"));
        assertEquals("FALLBACK", out.get("source"));
        assertNotNull(out.get("warning"), "空名单须带 L4 提示");
        assertTrue(String.valueOf(out.get("warning")).contains("资深名单为空"));
        assertTrue(List.of("laa-sales-a", "laa-sales-b").contains(out.get("ownerId")));
    }

    @Test
    void ruleHitAndFallback() {
        // 迁移 DEMO 有 B 级通配规则，先停用以保证回落场景纯净
        jdbc.update("UPDATE erp_crm_assign_rule SET ENABLED='0' WHERE ID='RULE-DEMO-B-ALL'");
        // B 级规则：区域华东 → sales-a
        CrmAssignRule rule = new CrmAssignRule();
        rule.setGrade("B");
        rule.setRegion("华东");
        rule.setTargetEmpIds("laa-sales-a");
        rule.setPriority(10);
        autoAssignService.saveRule(rule);
        Lead hit = seedLead("b1", "B", "华东", null);
        Map<String, Object> out1 = autoAssignService.route(hit);
        assertEquals("laa-sales-a", out1.get("ownerId"), "规则命中目标集：" + out1);
        assertEquals("RULE", out1.get("source"));
        // B 级华南无规则 → 回落全员
        Lead miss = seedLead("b2", "B", "华南", null);
        Map<String, Object> out2 = autoAssignService.route(miss);
        assertEquals("FALLBACK", out2.get("source"));
        assertTrue(List.of("laa-sales-a", "laa-sales-b").contains(out2.get("ownerId")));
    }

    @Test
    void skipAssignedAndPool() {
        Lead assigned = seedLead("c1", "A", null, "laa-sales-a");
        Map<String, Object> out1 = autoAssignService.route(assigned);
        assertEquals(false, out1.get("assigned"));
        assertEquals("SKIP_ASSIGNED", out1.get("reason"));
        // 已分配线索改分不重派
        assertEquals("laa-sales-a", ownerOf(assigned.getId()));
        // 池内线索 SKIP
        jdbc.update("INSERT INTO erp_crm_lead_pool (ID, LEAD_ID, REASON, GRADE_AT_ENTRY, POOLED_AT, STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('laa-pool-1', 'laa-lead-c2', 'D_GRADE', 'D', NOW(), "
                + "'PENDING', 'junit', '0', 0)");
        Lead pooled = seedLead("c2", "D", null, null);
        jdbc.update("UPDATE erp_crm_lead SET POOL_FLAG='1' WHERE ID='laa-lead-c2'");
        pooled.setPoolFlag("1");
        Map<String, Object> out2 = autoAssignService.route(pooled);
        assertEquals("SKIP_ASSIGNED", out2.get("reason"));
    }

    @Test
    void configValidationAndFullReplace() {
        // 空目标集 422
        CrmAssignRule bad = new CrmAssignRule();
        bad.setGrade("B");
        bad.setTargetEmpIds(" ");
        assertThrows(ServiceException.class, () -> autoAssignService.saveRule(bad));
        // 资深名单全量覆盖：先标 a，再只保存 b → a 清除
        markSenior("laa-sales-a");
        autoAssignService.saveSeniors(List.of(Map.of("empId", "laa-sales-b", "empName", "laa-sales-b")));
        Integer seniorA = jdbc.queryForObject("SELECT COUNT(*) FROM erp_crm_sales_profile "
                + "WHERE EMP_ID='laa-sales-a' AND IS_SENIOR='1'", Integer.class);
        Integer seniorB = jdbc.queryForObject("SELECT COUNT(*) FROM erp_crm_sales_profile "
                + "WHERE EMP_ID='laa-sales-b' AND IS_SENIOR='1'", Integer.class);
        assertEquals(0, seniorA, "未勾选的资深标记被清除");
        assertEquals(1, seniorB);
    }

    @Test
    void scoreHookRoutesEndToEnd() {
        markSenior("laa-sales-a");
        Lead lead = new Lead();
        lead.setSourceChannel("WEB");
        lead.setCompanyName("钩子公司" + UUID.randomUUID().toString().substring(0, 6));
        lead.setContactName("钩子联系人");
        lead.setRegion("华北");
        Lead created = leadService.create(lead);
        assertEquals(null, created.getOwnerId(), "录入未指定跟进人");
        // 五维全 5 → A 级 → 自动分配挂钩
        Map<String, Object> score = leadService.score(created.getId(), 5, 5, 5, 5, 5);
        assertEquals("A", score.get("grade"));
        assertEquals("laa-sales-a", score.get("autoAssignedTo"), "评分挂钩自动分配：" + score);
        assertEquals("AUTO", score.get("autoAssignSource"));
        assertEquals("laa-sales-a", ownerOf(created.getId()));
    }
}
