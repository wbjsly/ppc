package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmIpAgreementService;
import com.erp.service.pm.PmAchievementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 研发成果 DbTest（ip-management spec：WBS L1 / 工时提取 / 判定四路径（含独立缺协议 L4 放行、
 * 合作缺协议 L1）/ 同任务同类型 409 / 参与人快照落库）。造数前缀 ipa-。
 */
@SpringBootTest
class IpAchievementDbTest {

    @Autowired
    private PmAchievementService achievementService;
    @Autowired
    private PmIpAgreementService agreementService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String taskId;

    @BeforeEach
    void setUp() {
        purge();
        projectId = seedProject("研发");
        taskId = seedTask();
        authAs("ipa-pm", "PM");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---------- 9.2 登记校验 ----------

    @Test
    void missingWbsBlockedL1() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> achievementService.save(basePayload(null)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("WBS"), e.getMessage());
    }

    @Test
    void independentProjectMissingAgreementPassesWithL4() {
        // 独立研发（projType=研发且无合同）缺协议 → L4 放行判公司 100%
        Map<String, Object> out = achievementService.save(basePayload(taskId));
        assertEquals("{\"company\":100}", out.get("ownershipResult"));
        assertNotNull(out.get("l4Warning"), "独立研发缺协议 L4 提示");
        assertEquals("DRAFT", out.get("status"));
    }

    @Test
    void coopProjectMissingAgreementBlocksL1() {
        // 合作项目（有合同号）缺协议 → L1 阻断
        jdbc.update("UPDATE erp_pm_project SET CONTRACT_ID='ipa-c1', CONTRACT_NO='CT-IPA' WHERE ID=?", projectId);
        ServiceException e = assertThrows(ServiceException.class,
                () -> achievementService.save(basePayload(taskId)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("协议缺失"), e.getMessage());
    }

    @Test
    void coopWithAgreementJudgesByRatio() {
        authAs("ipa-legal", "LEGAL");
        agreementService.save(coopPayload(70, 30));
        authAs("ipa-pm", "PM");
        Map<String, Object> out = achievementService.save(basePayload(taskId));
        String own = String.valueOf(out.get("ownershipResult"));
        assertTrue(own.contains("70") && own.contains("30"), "合作按比例判定：" + own);
    }

    @Test
    void entrustJudgesToEntrustee() {
        authAs("ipa-legal", "LEGAL");
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("coopMode", "ENTRUST");
        p.put("entrusteeName", "IPA 委托方客户");
        p.put("signDate", java.time.LocalDate.now().toString());
        agreementService.save(p);
        authAs("ipa-pm", "PM");
        Map<String, Object> out = achievementService.save(basePayload(taskId));
        assertTrue(String.valueOf(out.get("ownershipResult")).contains("IPA 委托方客户"));
    }

    @Test
    void shortDescriptionOnlyWarns() {
        Map<String, Object> p = basePayload(taskId);
        p.put("description", "短");
        Map<String, Object> out = achievementService.save(p);
        assertNotNull(out.get("l4Warning"), "描述<20 字 L4 提示不阻断");
    }

    @Test
    void sameTaskSameTypeBlocked409() {
        achievementService.save(basePayload(taskId));
        ServiceException e = assertThrows(ServiceException.class,
                () -> achievementService.save(basePayload(taskId)));
        assertEquals(409, e.getCode());
    }

    @Test
    void membersSnapshotPersisted() {
        Map<String, Object> p = basePayload(taskId);
        p.put("members", List.of(Map.of("empId", "ipa-emp1", "empName", "员工一", "hours", 12)));
        achievementService.save(p);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_achievement_member WHERE EMP_ID='ipa-emp1'",
                Integer.class), "参与人快照落库");
    }

    // ---------- helpers ----------

    private Map<String, Object> basePayload(String wbsId) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("wbsId", wbsId);
        p.put("type", "PATENT");
        p.put("name", "IPA 一种测试发明专利");
        p.put("description", "IPA 测试成果描述（20 字以上的完整说明）");
        p.put("completeDate", java.time.LocalDate.now().toString());
        return p;
    }

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

    private String seedProject(String projType) {
        String id = "ipa-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'IPA 成果项目', ?, 'INTERNAL', 'NS-IPA', 'ipa-pm', "
                + "DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                + "'ACTIVE', '1', 1000, 0.10, '0', '0', 'junit', NOW(), '0', 0)", id, projType);
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
        jdbc.update("DELETE FROM erp_pm_achievement_member WHERE ACHIEVE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'ipa-%') t)");
        jdbc.update("DELETE FROM erp_pm_achievement WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_ip_agreement WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'ipa-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID IN ('ipa-legal', 'ipa-pm')");
    }
}
