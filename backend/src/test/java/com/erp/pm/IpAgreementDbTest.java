package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmIpAgreementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * IP 归属协议 DbTest（ip-management spec：协议防重后签覆盖 / 合作比例=100% 校验 /
 * 立项卡控 422 / 维护角色 403）。造数前缀 ipa-。
 */
@SpringBootTest
class IpAgreementDbTest {

    @Autowired
    private PmIpAgreementService agreementService;
    @Autowired
    private com.erp.service.pm.PmBudgetService budgetService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;

    @BeforeEach
    void setUp() {
        purge();
        projectId = seedProject("研发");
        authAs("ipa-legal", "LEGAL");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---------- 9.1 协议防重（后签覆盖）+ 比例校验 + 角色 + 立项卡控 ----------

    @Test
    void coopRateMustSum100() {
        Map<String, Object> p = coopPayload(60, 30);
        ServiceException e = assertThrows(ServiceException.class, () -> agreementService.save(p));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("100"), e.getMessage());
    }

    @Test
    void coopRate100Saves() {
        Map<String, Object> out = agreementService.save(coopPayload(60, 40));
        assertNotNull(out.get("id"));
        assertEquals("1", out.get("effective"));
    }

    @Test
    void laterAgreementSupersedesEarlier() {
        agreementService.save(coopPayload(60, 40));
        Map<String, Object> second = agreementService.save(indepPayload());
        // 新协议生效，旧协议置 0 留痕
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_ip_agreement WHERE PROJECT_ID=? AND EFFECTIVE='1' AND ID=?",
                Integer.class, projectId, second.get("id")), "仅新协议生效");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_ip_agreement WHERE PROJECT_ID=? AND EFFECTIVE='0'",
                Integer.class, projectId), "旧协议留痕可查");
    }

    @Test
    void nonLegalCannotMaintain() {
        authAs("ipa-pm", "PM");
        ServiceException e = assertThrows(ServiceException.class, () -> agreementService.save(indepPayload()));
        assertEquals(403, e.getCode());
    }

    @Test
    void baselineSubmitBlockedWithoutAgreement() {
        // 造"可立项"状态（WBS 阶段+任务 + 预算行），项目须为 DRAFT（submitBaseline 只受理草稿）
        seedBaselineReadyWbsAndBudget();
        jdbc.update("UPDATE erp_pm_project SET STATUS='DRAFT' WHERE ID=?", projectId);
        authAs("ipa-pm", "PM");
        // 无协议 → 提交基线审批（立项）被卡控 422（BR-4.8-24 / C-4.8-08）
        ServiceException e = assertThrows(ServiceException.class,
                () -> budgetService.submitBaseline(projectId));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("IP 归属协议缺失"), e.getMessage());
        // 补协议后 IP 卡控放行（会继续走到审批提交，不再因 IP 报错）
        authAs("ipa-legal", "LEGAL");
        agreementService.save(indepPayload());
        authAs("ipa-pm", "PM");
        // 此时无 PMO 审批底座造数，只要不是"IP 归属协议缺失"即证明卡控已放行
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> budgetService.submitBaseline(projectId));
        assertTrue(!e2.getMessage().contains("IP 归属协议缺失"),
                "补协议后 IP 卡控放行（当前报错：" + e2.getMessage() + "）");
    }

    // ---------- helpers ----------

    private Map<String, Object> coopPayload(int companyRate, int partnerRate) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("coopMode", "COOP");
        p.put("companyRate", companyRate);
        p.put("partnerRate", partnerRate);
        p.put("partnerName", "IPA 合作方");
        p.put("signDate", java.time.LocalDate.now().toString());
        return p;
    }

    private Map<String, Object> indepPayload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("coopMode", "INDEP");
        p.put("signDate", java.time.LocalDate.now().toString());
        return p;
    }

    private String seedProject(String projType) {
        String id = "ipa-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'IPA 协议项目', ?, 'INTERNAL', 'NS-IPA', 'ipa-pm', "
                + "DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                + "'ACTIVE', '1', 1000, 0.10, '0', '0', 'junit', NOW(), '0', 0)", id, projType);
        return id;
    }

    /** 造可立项状态：三层 WBS（阶段→工作包→任务）+ 预算行（labor=1000） */
    private void seedBaselineReadyWbsAndBudget() {
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES ('ipa-phase', ?, 'IPA1PH', 1, 'IPA 阶段', "
                + "'junit', NOW(), '0', 0)", projectId);
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES ('ipa-pkg', ?, 'IPA1PK', 2, 'IPA 工作包', "
                + "'ipa-phase', 'junit', NOW(), '0', 0)", projectId);
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, "
                + "OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('ipa-task', ?, 'IPA1T', 3, 'IPA 任务', 'ipa-pkg', 'ipa-pm', 'JUNIOR', "
                + "'junit', NOW(), '0', 0)", projectId);
        jdbc.update("INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, "
                + "SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('ipa-bl', ?, 'ipa-task', 1000, 0, 0, 0, 0, 0, 'junit', NOW(), '0', 0)", projectId);
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, "x",
                java.util.Arrays.stream(roles)
                        .map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                        .toList()));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID IN ('ipa-legal', 'ipa-pm')");
    }
}
