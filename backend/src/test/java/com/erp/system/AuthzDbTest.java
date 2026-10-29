package com.erp.system;

import com.erp.common.GlobalExceptionHandler;
import com.erp.common.ServiceException;
import com.erp.security.AuthzDenyCounter;
import com.erp.security.AuthzDenyFilter;
import com.erp.security.JwtTokenProvider;
import com.erp.service.AuthzIncidentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 越权检测全链（add-authz-detection-console spec authz-detection：BR-5.6-11 / S-5.6-06）：
 * 窗口计数达阈值触发（幂等+双角色告警+SECURITY_EVENT）/ 敏感路径降权拦截 /
 * 正常业务放行 / 到期与手动恢复幂等 / 处置三动作（结论 422/重复 422/恶意联动停用）/ 敏感清单断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthzDbTest {

    @Autowired
    private AuthzDenyCounter counter;
    @Autowired
    private AuthzIncidentService incidentService;
    @Autowired
    private GlobalExceptionHandler exceptionHandler;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String target;

    @BeforeEach
    void setUp() {
        purge();
        counter.reset();
        target = seedUser("azdt-tgt");
        grantRole(target, "ROLE_PLANNER");
    }

    @AfterEach
    void tearDown() {
        purge();
        counter.reset();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.execute("DELETE FROM erp_authz_incident");
        jdbc.execute("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='AuthzAlert'");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE FIELD_NAME='SECURITY_EVENT' "
                + "AND EMP_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'azdt-%') t); "
                + "SET @audit_bypass=0");
        jdbc.execute("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'azdt-%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'azdt-%'");
    }

    private String seedUser(String name) {
        String id = UUID.randomUUID().toString().replace("-", "");
        // 复制 admin 真实密码哈希（登录断言用 Admin@123；{noop} 空密码口径无法走登录）
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) SELECT ?,?,PASSWORD,?,'1','azdt-test','0',0 "
                        + "FROM erp_admin_user WHERE USERNAME='admin'",
                id, name, "越权测试");
        return id;
    }

    private void grantRole(String userId, String roleCode) {
        jdbc.update("INSERT INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) "
                + "SELECT ?,?,ID FROM erp_admin_role WHERE ROLE_CODE=?",
                UUID.randomUUID().toString().replace("-", ""), userId, roleCode);
    }

    private void login(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private String tokenFor(String userId, String... roles) {
        return jwtTokenProvider.generateToken(userId, Map.of("roles", List.of(roles)));
    }

    // ---------------- 2.1 计数与触发 ----------------

    @Test
    void counterTriggersAtThresholdAndIsIdempotent() {
        // 路 A：业务 403 经 GlobalExceptionHandler 计数（HTTP 200+body）
        login(target);
        for (int i = 0; i < 9; i++) {
            exceptionHandler.handleServiceException(new ServiceException(403, "业务拒绝 " + i));
        }
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_authz_incident WHERE TARGET_USER_ID=?", Integer.class, target);
        assertEquals(0, before, "9 次 < 阈值 10 不触发");
        exceptionHandler.handleServiceException(new ServiceException(403, "第 10 次"));
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_authz_incident WHERE TARGET_USER_ID=?", Integer.class, target);
        assertEquals(1, cnt, "达阈值触发");
        Map<String, Object> inc = jdbc.queryForList(
                "SELECT HIT_COUNT, THRESHOLD, STATUS, EXPIRE_AT FROM erp_authz_incident "
                        + "WHERE TARGET_USER_ID=?", target).get(0);
        assertEquals(10, ((Number) inc.get("HIT_COUNT")).intValue());
        assertEquals(10, ((Number) inc.get("THRESHOLD")).intValue());
        assertEquals("ACTIVE", inc.get("STATUS"));
        // 双角色告警（ADMIN + UAR_AUDITOR）
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='AuthzAlert' AND BIZ_ID="
                        + "(SELECT ID FROM erp_authz_incident WHERE TARGET_USER_ID=? LIMIT 1)",
                Integer.class, target);
        assertTrue(notices >= 2, "ADMIN 与内审各至少 1 条（实际 " + notices + "）");
        // SECURITY_EVENT 审计落库
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE FIELD_NAME='SECURITY_EVENT' AND EMP_ID=?",
                Integer.class, target);
        assertTrue(audits >= 1, "SECURITY_EVENT 审计");
        // 幂等：存续期继续 hit → 不新增事件
        for (int i = 0; i < 12; i++) {
            exceptionHandler.handleServiceException(new ServiceException(403, "存续期重复"));
        }
        Integer cnt2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_authz_incident WHERE TARGET_USER_ID=?", Integer.class, target);
        assertEquals(1, cnt2, "存续期幂等不重复打标");
        // 非 403 不计数
        exceptionHandler.handleServiceException(new ServiceException(422, "参数错"));
        exceptionHandler.handleServiceException(new ServiceException(500, "内部错"));
        Integer cnt3 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_authz_incident WHERE TARGET_USER_ID=?", Integer.class, target);
        assertEquals(1, cnt3, "非 403 不计数");
    }

    @Test
    void sensitivePathConstantsComplete() {
        // design D5 清单断言：四前缀必须存在
        assertEquals(4, AuthzDenyFilter.SENSITIVE_PREFIXES.length);
        List<String> prefixes = List.of(AuthzDenyFilter.SENSITIVE_PREFIXES);
        assertTrue(prefixes.contains("/api/system/"));
        assertTrue(prefixes.contains("/api/mdm/"));
        assertTrue(prefixes.contains("/api/fin/"));
        assertTrue(prefixes.contains("/api/qms/"));
    }

    // ---------------- 3.1 降权拦截 ----------------

    @Test
    void restrictedUserBlockedOnSensitiveAllowedOnNormal() throws Exception {
        // 造 ACTIVE 降权事件
        incidentService.trigger(target, 12, 10);
        assertTrue(incidentService.hasActiveRestriction(target));
        String token = tokenFor(target, "ROLE_PLANNER");

        // 敏感路径 → 403 降权提示
        mockMvc.perform(get("/api/system/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("临时降权中")));
        // 敏感路径计数（路 B：HTTP 403 → counter.hit → 已有 ACTIVE 行 → 幂等）
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_authz_incident WHERE TARGET_USER_ID=?", Integer.class, target);
        assertEquals(1, cnt, "降权 403 不重复打标");

        // 非敏感路径（/api/auth/info）→ 不因降权被拦（200）
        mockMvc.perform(get("/api/auth/info").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 降权不阻断登录（spec：登录成功）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"azdt-tgt\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 恢复后敏感路径不再返回"降权"提示（是否 200 取决于角色 URL 规则，仅断言降权语义消失）
        incidentService.restoreNow(firstIncidentId(), "测试恢复");
        String after = mockMvc.perform(get("/api/system/roles").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertFalse(after.contains("临时降权中"), "恢复后无降权提示：" + after);
    }

    // ---------------- 3.2 恢复 ----------------

    @Test
    void restoreExpiredAndManualRestoreIdempotent() {
        incidentService.trigger(target, 10, 10);
        String id = firstIncidentId();
        // 未到期：扫描不恢复
        Map<String, Object> sw0 = incidentService.restoreExpired();
        assertEquals(0, ((Number) sw0.get("restored")).intValue(), "未到期不恢复");
        // 回拨到期
        jdbc.update("UPDATE erp_authz_incident SET EXPIRE_AT=DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE ID=?", id);
        Map<String, Object> sw1 = incidentService.restoreExpired();
        assertEquals(1, ((Number) sw1.get("restored")).intValue(), "到期恢复");
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_authz_incident WHERE ID=?", String.class, id);
        assertEquals("RESTORED", st);
        String reason = jdbc.queryForObject(
                "SELECT RESTORE_REASON FROM erp_authz_incident WHERE ID=?", String.class, id);
        assertEquals("到期自动恢复", reason);
        // 幂等：重复扫描 0
        Map<String, Object> sw2 = incidentService.restoreExpired();
        assertEquals(0, ((Number) sw2.get("restored")).intValue(), "扫描幂等");
        assertFalse(incidentService.hasActiveRestriction(target), "恢复后不再拦截");

        // 手动提前恢复（幂等：非 ACTIVE 跳过）
        incidentService.trigger(target, 10, 10);
        String id2 = firstIncidentId();
        incidentService.restoreNow(id2, "提前恢复测试");
        String st2 = jdbc.queryForObject(
                "SELECT STATUS FROM erp_authz_incident WHERE ID=?", String.class, id2);
        assertEquals("RESTORED", st2);
        incidentService.restoreNow(id2, "重复调用");
        Integer restoredCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_authz_incident WHERE TARGET_USER_ID=? AND STATUS='RESTORED'",
                Integer.class, target);
        assertEquals(2, restoredCnt, "重复调用不产生新状态");
    }

    // ---------------- 4.1 处置三动作 ----------------

    @Test
    void disposeThreeActionsAndValidation() {
        incidentService.trigger(target, 10, 10);
        String id = firstIncidentId();

        // 结论过短 422
        ServiceException e1 = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> incidentService.dispose(id, "MISUSE", "太短", "0"));
        assertEquals(422, e1.getCode());
        // 非法动作 422
        ServiceException e2 = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> incidentService.dispose(id, "UNKNOWN", "足够长度的处置结论文本", "0"));
        assertEquals(422, e2.getCode());

        // MISUSE：留结论保持 ACTIVE
        incidentService.dispose(id, "MISUSE", "核实为批量查询脚本未加过滤条件，非恶意", "1");
        Map<String, Object> inc = jdbc.queryForList(
                "SELECT DISPOSITION, STATUS, TRAINING_FLAG, DISPOSITION_BY FROM erp_authz_incident WHERE ID=?", id).get(0);
        assertEquals("MISUSE", inc.get("DISPOSITION"));
        assertEquals("ACTIVE", inc.get("STATUS"), "误用保持降权至到期");
        assertEquals("1", inc.get("TRAINING_FLAG"));
        // 重复处置 422
        ServiceException e3 = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> incidentService.dispose(id, "RESTORE", "足够长度的处置结论文本", "0"));
        assertEquals(422, e3.getCode());
        // 处置审计 SECURITY_EVENT
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE FIELD_NAME='SECURITY_EVENT' AND EMP_ID=? "
                        + "AND REASON LIKE '%处置%'", Integer.class, target);
        assertTrue(audits >= 1, "处置审计落库");

        // MISUSE 行仍 ACTIVE → 先手动恢复，才能触发下一场景（trigger 幂等锚=活动行）
        incidentService.restoreNow(id, "场景切换恢复");
        // RESTORE：立即恢复
        incidentService.trigger(target, 10, 10);
        String id2 = firstIncidentId();
        incidentService.dispose(id2, "RESTORE", "确认为测试账号误触发，立即恢复", "0");
        String st2 = jdbc.queryForObject(
                "SELECT STATUS FROM erp_authz_incident WHERE ID=?", String.class, id2);
        assertEquals("RESTORED", st2);

        // MALICIOUS：联动 16.1 停用（STATUS='0'）+ 事件 DISABLED
        incidentService.trigger(target, 10, 10);
        String id3 = firstIncidentId();
        incidentService.dispose(id3, "MALICIOUS", "确认恶意探测行为，冻结账号并启动调查", "0");
        String userStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_user WHERE ID=?", String.class, target);
        assertEquals("0", userStatus, "恶意处置联动停用（16.1 强停，非归档）");
        String st3 = jdbc.queryForObject(
                "SELECT STATUS FROM erp_authz_incident WHERE ID=?", String.class, id3);
        assertEquals("DISABLED", st3);
        // 不存在 404
        ServiceException e4 = org.junit.jupiter.api.Assertions.assertThrows(ServiceException.class,
                () -> incidentService.dispose("no-such-id", "MISUSE", "足够长度的处置结论文本", "0"));
        assertEquals(404, e4.getCode());
    }

    // ---------------- 4.2 查询 ----------------

    @Test
    void listWithStatusFilter() {
        incidentService.trigger(target, 10, 10);
        String id = firstIncidentId();
        Map<String, Object> all = incidentService.list(null, 1, 50);
        assertTrue(((Number) all.get("total")).intValue() >= 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) all.get("rows");
        Map<String, Object> row = rows.stream()
                .filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals(target, row.get("targetUserId"));
        assertEquals(10, ((Number) row.get("hitCount")).intValue());
        assertEquals(10, ((Number) row.get("threshold")).intValue());
        assertEquals("ACTIVE", row.get("status"));
        // status 过滤
        Map<String, Object> activeOnly = incidentService.list("ACTIVE", 1, 50);
        assertTrue(((Number) activeOnly.get("total")).intValue() >= 1);
        Map<String, Object> disabledOnly = incidentService.list("DISABLED", 1, 50);
        assertEquals(0, ((Number) disabledOnly.get("total")).intValue());
    }

    private String firstIncidentId() {
        return jdbc.queryForObject(
                "SELECT ID FROM erp_authz_incident WHERE TARGET_USER_ID=? ORDER BY TRIGGERED_AT DESC LIMIT 1",
                String.class, target);
    }
}
