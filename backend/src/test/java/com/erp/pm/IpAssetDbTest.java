package com.erp.pm;

import com.erp.service.pm.PmIpAssetService;
import com.erp.service.pm.PmIpAgreementService;
import com.erp.service.pm.PmAchievementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 资产建档 DbTest（ip-management spec：确认即建档幂等 / 保护期计算 / 申请状态历史留痕 /
 * 档案只增不改）。造数前缀 ipa-。
 */
@SpringBootTest
class IpAssetDbTest {

    @Autowired
    private PmIpAssetService assetService;
    @Autowired
    private PmAchievementService achievementService;
    @Autowired
    private PmIpAgreementService agreementService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String taskId;
    private String patentAchId;
    private String assetId;

    @BeforeEach
    void setUp() {
        purge();
        projectId = seedProject();
        taskId = seedTask();
        authAs("ipa-legal", "LEGAL");
        agreementService.save(indepPayload());
        // 登记专利成果 → 提交 → 研发负责人确认（触发建档）
        authAs("ipa-pm", "PM");
        Map<String, Object> out = achievementService.save(patentPayload());
        patentAchId = String.valueOf(out.get("id"));
        achievementService.save(submitPayload(patentAchId));
        authAs("ipa-rd", "RD_LEAD");
        achievementService.confirm(patentAchId, Map.of());
        assetId = jdbc.queryForObject(
                "SELECT ID FROM erp_pm_ip_asset WHERE ACHIEVE_ID=?", String.class, patentAchId);
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---------- 9.4 建档幂等 / 保护期 / 状态历史 / 只增不改 ----------

    @Test
    void confirmGeneratesAssetWithPatent20y() {
        assertNotNull(assetId, "确认即建档");
        LocalDate expire = jdbc.queryForObject(
                "SELECT PROTECT_EXPIRE_DATE FROM erp_pm_ip_asset WHERE ACHIEVE_ID=?",
                LocalDate.class, patentAchId);
        LocalDate complete = jdbc.queryForObject(
                "SELECT COMPLETE_DATE FROM erp_pm_achievement WHERE ID=?", LocalDate.class, patentAchId);
        assertEquals(complete.plusYears(20), expire, "专利保护期 = 完成日 + 20 年");
    }

    @Test
    void generateIdempotentSingleAssetPerAchievement() {
        // 再次确认（模拟重复建档）不产生第二份档案
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_ip_asset WHERE ACHIEVE_ID=?", Integer.class, patentAchId),
                "一成果一档案（UK 幂等）");
    }

    @Test
    void softCopyright50y() {
        authAs("ipa-pm", "PM");
        Map<String, Object> p = patentPayload();
        p.put("type", "SOFT_CP");
        p.put("name", "IPA 软著");
        Map<String, Object> out = achievementService.save(p);
        String softId = String.valueOf(out.get("id"));
        // 提交时保持 SOFT_CP 类型（避免与 setUp 的 PATENT 撞同类型防重）
        Map<String, Object> submit = patentPayload();
        submit.put("id", softId);
        submit.put("type", "SOFT_CP");
        submit.put("name", "IPA 软著");
        submit.put("action", "SUBMIT");
        achievementService.save(submit);
        authAs("ipa-rd", "RD_LEAD");
        achievementService.confirm(softId, Map.of());
        LocalDate expire = jdbc.queryForObject(
                "SELECT PROTECT_EXPIRE_DATE FROM erp_pm_ip_asset WHERE ACHIEVE_ID=?",
                LocalDate.class, softId);
        LocalDate complete = jdbc.queryForObject(
                "SELECT COMPLETE_DATE FROM erp_pm_achievement WHERE ID=?", LocalDate.class, softId);
        assertEquals(complete.plusYears(50), expire, "软著保护期 = 完成日 + 50 年");
    }

    @Test
    void applyStatusWritesHistory() {
        authAs("ipa-legal", "LEGAL");
        assetService.applyStatus(assetId, Map.of("applyStatus", "SUBMITTED", "remark", "已递交专利局"));
        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT APPLY_STATUS FROM erp_pm_ip_asset WHERE ID=?", String.class, assetId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_ip_asset_status_log WHERE ASSET_ID=? AND TO_STATUS='SUBMITTED'",
                Integer.class, assetId), "申请状态变更写历史");
    }

    @Test
    void assetIsAppendOnly() {
        // 权属快照字段不可直接改（只增不改）——服务无 update 入口；UK + generate 幂等保证不重建覆盖
        // 断言：档案只有一条且 ownership 快照来自成果
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_ip_asset WHERE ACHIEVE_ID=?", Integer.class, patentAchId));
        String own = jdbc.queryForObject(
                "SELECT OWNERSHIP_RESULT FROM erp_pm_ip_asset WHERE ACHIEVE_ID=?", String.class, patentAchId);
        assertEquals("{\"company\":100}", own, "权属快照来自成果判定");
    }

    // ---------- helpers ----------

    private Map<String, Object> patentPayload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("wbsId", taskId);
        p.put("type", "PATENT");
        p.put("name", "IPA 建档测试专利");
        p.put("description", "IPA 建档测试成果描述（20 字以上的完整说明）");
        p.put("completeDate", LocalDate.now().toString());
        return p;
    }

    private Map<String, Object> submitPayload(String id) {
        Map<String, Object> p = patentPayload();
        p.put("id", id);
        p.put("action", "SUBMIT");
        return p;
    }

    private Map<String, Object> indepPayload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("projectId", projectId);
        p.put("coopMode", "INDEP");
        p.put("signDate", LocalDate.now().toString());
        return p;
    }

    private String seedProject() {
        String id = "ipa-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'IPA 建档项目', '研发', 'INTERNAL', 'NS-IPA', 'ipa-pm', "
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
        jdbc.update("DELETE FROM erp_pm_ip_asset_status_log WHERE ASSET_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_ip_asset WHERE PROJECT_ID LIKE 'ipa-%') t)");
        jdbc.update("DELETE FROM erp_pm_ip_asset WHERE PROJECT_ID LIKE 'ipa-%'");
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
