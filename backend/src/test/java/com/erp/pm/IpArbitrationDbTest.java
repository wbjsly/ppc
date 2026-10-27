package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmIpArbitrationService;
import com.erp.service.pm.PmAchievementService;
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
 * 仲裁 DbTest（ip-management spec：双入口 / 在途防重 409 / 状态机全流转 /
 * 裁决回写重确认 / CONFIRMED 禁改禁删 422）。造数前缀 ipa-。
 */
@SpringBootTest
class IpArbitrationDbTest {

    @Autowired
    private PmIpArbitrationService arbitrationService;
    @Autowired
    private PmAchievementService achievementService;
    @Autowired
    private PmIpAgreementService agreementService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String taskId;
    private String achieveId;

    @BeforeEach
    void setUp() {
        purge();
        projectId = seedProject();
        taskId = seedTask();
        authAs("ipa-legal", "LEGAL");
        agreementService.save(indepPayload());
        // 登记 + 提交确认 + 研发负责人确认 → CONFIRMED
        authAs("ipa-pm", "PM");
        Map<String, Object> out = achievementService.save(basePayload());
        achieveId = String.valueOf(out.get("id"));
        achievementService.save(basePayloadWithAction("SUBMIT", achieveId));
        authAs("ipa-rd", "RD_LEAD");
        achievementService.confirm(achieveId, Map.of());
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---------- 9.3 双入口 + 防重 + 状态机 + 裁决回写 + CONFIRMED 禁改 ----------

    @Test
    void manualCreateThenDuplicateBlocked409() {
        authAs("ipa-legal", "LEGAL");
        arbitrationService.createManual(Map.of("achieveId", achieveId));
        ServiceException e = assertThrows(ServiceException.class,
                () -> arbitrationService.createManual(Map.of("achieveId", achieveId)));
        assertEquals(409, e.getCode(), "同一成果在途仲裁单防重");
    }

    @Test
    void fullFlowAdjudicateWritesBackAndReconfirm() {
        // 独立申请 → 主张 → 意见 → 双方确认 → 裁决回写 → 成果回待确认
        authAs("ipa-legal", "LEGAL");
        Map<String, Object> arb = arbitrationService.createManual(Map.of("achieveId", achieveId));
        String arbId = String.valueOf(arb.get("id"));
        arbitrationService.addClaim(arbId, Map.of("claimant", "PARTY_A", "claimantName", "公司", "claimRate", 50));
        arbitrationService.addClaim(arbId, Map.of("claimant", "PARTY_B", "claimantName", "合作方", "claimRate", 50));
        arbitrationService.issueOpinion(arbId, Map.of("opinion", "按贡献各半"));
        authAs("ipa-rd", "RD_LEAD");
        arbitrationService.confirmOpinion(arbId, Map.of("action", "ACCEPT"));
        authAs("ipa-legal", "LEGAL");
        Map<String, Object> adj = arbitrationService.adjudicate(arbId,
                Map.of("adjudicateResult", "{\"company\":50,\"partner\":50}"));
        assertEquals("ADJUDICATED", adj.get("status"));
        // 成果权属被覆盖 + 回待确认
        assertEquals("{\"company\":50,\"partner\":50}", jdbc.queryForObject(
                "SELECT OWNERSHIP_RESULT FROM erp_pm_achievement WHERE ID=?", String.class, achieveId));
        assertEquals("PENDING_CONFIRM", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_achievement WHERE ID=?", String.class, achieveId));
        assertEquals("0", jdbc.queryForObject(
                "SELECT ARBITRATING_FLAG FROM erp_pm_achievement WHERE ID=?", String.class, achieveId));
    }

    @Test
    void rejectOpinionEscalatesExternal() {
        authAs("ipa-legal", "LEGAL");
        Map<String, Object> arb = arbitrationService.createManual(Map.of("achieveId", achieveId));
        String arbId = String.valueOf(arb.get("id"));
        arbitrationService.issueOpinion(arbId, Map.of("opinion", "公司全权"));
        authAs("ipa-rd", "RD_LEAD");
        Map<String, Object> out = arbitrationService.confirmOpinion(arbId,
                Map.of("action", "REJECT", "reason", "不接受，诉诸法律"));
        assertEquals("ESCALATED_EXTERNAL", out.get("status"));
    }

    @Test
    void confirmedAchievementCannotEditOrDelete() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> achievementService.save(basePayloadWithId(achieveId)));
        assertEquals(422, e.getCode(), "已确认成果禁改（BR-4.8-26）");
        assertTrue(e.getMessage().contains("永久保留"), e.getMessage());
    }

    // ---------- helpers ----------

    private Map<String, Object> basePayload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("wbsId", taskId);
        p.put("type", "PATENT");
        p.put("name", "IPA 仲裁测试专利");
        p.put("description", "IPA 仲裁测试成果描述（20 字以上的完整说明）");
        p.put("completeDate", java.time.LocalDate.now().toString());
        return p;
    }

    private Map<String, Object> basePayloadWithAction(String action, String id) {
        Map<String, Object> p = basePayload();
        p.put("id", id);
        p.put("action", action);
        return p;
    }

    private Map<String, Object> basePayloadWithId(String id) {
        Map<String, Object> p = basePayload();
        p.put("id", id);
        p.put("name", "IPA 被改的名称");
        return p;
    }

    private Map<String, Object> indepPayload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("coopMode", "INDEP");
        p.put("signDate", java.time.LocalDate.now().toString());
        return p;
    }

    private String seedProject() {
        String id = "ipa-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'IPA 仲裁项目', '研发', 'INTERNAL', 'NS-IPA', 'ipa-pm', "
                + "DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                + "'ACTIVE', '1', 1000, 0.10, '0', '0', 'junit', NOW(), '0', 0)", id);
        return id;
    }

    private String seedTask() {
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES ('ipa-phase', ?, 'IPA1PH', 1, 'IPA 阶段', "
                + "'junit', NOW(), '0', 0)", projectId);
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, "
                + "OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('ipa-task', ?, 'IPA1T', 3, 'IPA 任务', 'ipa-phase', 'ipa-pm', 'JUNIOR', "
                + "'junit', NOW(), '0', 0)", projectId);
        return "ipa-task";
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, "x",
                java.util.Arrays.stream(roles)
                        .map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                        .toList()));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pm_ip_claim WHERE ARB_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_ip_arbitration WHERE ACHIEVE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'ipa-%') t2)) t)");
        jdbc.update("DELETE FROM erp_pm_ip_arbitration WHERE ACHIEVE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'ipa-%') t)");
        jdbc.update("DELETE FROM erp_pm_achievement_member WHERE ACHIEVE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'ipa-%') t)");
        jdbc.update("DELETE FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID IN ('ipa-legal', 'ipa-pm', 'ipa-rd')");
    }
}
