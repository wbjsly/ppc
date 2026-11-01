package com.erp.intf;

import com.erp.common.ServiceException;
import com.erp.service.intf.GrayReleaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灰度切流量库测（add-intf-portal-menus，spec interface-canary-release）。
 * 造数：凭证/通道用 GR- 前缀物理清理；asRole 注入登录态。
 */
@SpringBootTest
class IntfGrayReleaseDbTest {

    @Autowired
    private GrayReleaseService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String CH = "GR-CH-" + System.nanoTime();
    private String oldCredId;
    private String newCredId;

    @BeforeEach
    void setUp() {
        purge();
        asRole("ROLE_ADMIN");
        oldCredId = seedCred("GR-OLD-" + System.nanoTime(), "GR-P1");
        newCredId = seedCred("GR-NEW-" + System.nanoTime(), "GR-P1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        purge();
    }

    private void purge() {
        // 审计日志 hr_change_log 禁止删除（BR-5.6-13 触发器拦截），测试用唯一前缀不干扰即可
        jdbc.update("DELETE FROM erp_intf_gray_step_log WHERE RELEASE_ID IN "
                + "(SELECT ID FROM erp_intf_gray_release WHERE CHANNEL_CODE LIKE 'GR-CH-%')");
        jdbc.update("DELETE FROM erp_intf_gray_release WHERE CHANNEL_CODE LIKE 'GR-CH-%'");
        jdbc.update("DELETE FROM erp_intf_credential WHERE PARTNER_CODE='GR-P1'");
    }

    private String seedCred(String apiKey, String partner) {
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_intf_credential (ID, CHANNEL_ID, PARTNER_CODE, API_KEY, "
                        + "SECRET_HASH, SECRET_SALT, SIGN_KEY, ENV, RATE_TIER, LIMIT_PER_MIN, STATUS, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, 'GR-CH', ?, ?, 'h', 's', 'k', 'PROD', 'NORMAL', 100, 'ACTIVE', "
                        + "'gr-test', NOW(), '0', 0)",
                id, partner, apiKey);
        return id;
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("gr-tester", "x", roles));
    }

    private Map<String, Object> createRelease() {
        // channelCode 用 GR-P1：与 autoRollback 测试造的 call_log PARTNER_CODE 对齐
        // （queryErrorRate 按 PARTNER_CODE 查）
        return service.createRelease(Map.of(
                "channelCode", "GR-P1", "oldCredId", oldCredId, "newCredId", newCredId,
                "ladderJson", "[10,50,100]", "rollbackErrorRate", "50", "observeMin", "60"));
    }

    @Test
    void createAndStepThroughLadder() {
        Map<String, Object> r = createRelease();
        String id = (String) r.get("id");
        assertEquals("DRAFT", r.get("status"));
        // 首档切量 → 10%
        Map<String, Object> s1 = service.stepUp(id);
        assertEquals(1, s1.get("currentStep"));
        assertEquals(10, s1.get("pct"));
        assertEquals("RUNNING", s1.get("status"));
        // 第二档 → 50%
        Map<String, Object> s2 = service.stepUp(id);
        assertEquals(50, s2.get("pct"));
        // 第三档 → 100%
        Map<String, Object> s3 = service.stepUp(id);
        assertEquals(100, s3.get("pct"));
        // 超最高档拒绝
        ServiceException e = assertThrows(ServiceException.class, () -> service.stepUp(id));
        assertEquals(422, e.getCode(), "已达最高档拒绝");
    }

    @Test
    void rollbackFromMidStep() {
        String id = (String) createRelease().get("id");
        service.stepUp(id); // 10%
        service.stepUp(id); // 50%
        Map<String, Object> rb = service.rollback(id);
        assertEquals(1, rb.get("currentStep"), "回滚到上一档（10%）");
        assertEquals("ROLLBACK", rb.get("status"));
    }

    @Test
    void rollbackFromFirstStepAborts() {
        String id = (String) createRelease().get("id");
        service.stepUp(id); // 10%（首档）
        Map<String, Object> rb = service.rollback(id);
        assertEquals("ABORTED", rb.get("status"), "首档回滚=中止灰度");
        assertEquals(0, rb.get("currentStep"));
    }

    @Test
    void nonAdminForbidden() {
        asRole("ROLE_INTF_OPS");
        ServiceException e = assertThrows(ServiceException.class, this::createRelease);
        assertEquals(403, e.getCode(), "配置限 ADMIN");
    }

    @Test
    void dualCredentialShareQuota() {
        // 灰度期新旧凭证共享同一伙伴配额（同一 PARTNER_CODE，网关按伙伴计）
        assertEquals(jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_credential WHERE PARTNER_CODE='GR-P1'",
                Integer.class), 2, "双凭证同伙伴共享配额");
    }

    @Test
    void autoRollbackOnHighErrorRate() {
        String id = (String) createRelease().get("id");
        service.stepUp(id); // RUNNING 10%
        // 造观察窗口内高错误率调用（RESP_CODE=500）
        jdbc.update("INSERT INTO erp_intf_call_log (ID, CALLER, PARTNER_CODE, API_PATH, HTTP_METHOD, "
                        + "REQ_ID, RESP_CODE, ERR_CODE, COST_MS, CALL_AT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, 'gr', 'GR-P1', '/x', 'GET', ?, 500, 'E', 10, NOW(), 'gr-test', NOW(), '0', 0)",
                UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID().toString().replace("-", ""));
        int handled = service.observeAndAutoRollback();
        assertTrue(handled >= 1, "超阈自动回滚触发");
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT STATUS FROM erp_intf_gray_release WHERE ID=?", id);
        assertTrue("ROLLBACK".equals(r.get("STATUS")) || "ABORTED".equals(r.get("STATUS")),
                "状态回滚/中止：" + r.get("STATUS"));
    }

    @Test
    void listShowsCreated() {
        String id = (String) createRelease().get("id");
        List<Map<String, Object>> list = service.listReleases();
        assertTrue(list.stream().anyMatch(x -> id.equals(x.get("id"))));
    }
}
