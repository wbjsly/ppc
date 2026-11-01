package com.erp.intf;

import com.erp.common.ServiceException;
import com.erp.service.intf.SsoService;
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
 * SSO（本系统作 IdP）库测（add-intf-portal-menus，spec interface-sso）。
 * 造数：APP_ID 用 smk-sso- 前缀物理清理；asRole 注入登录态。
 */
@SpringBootTest
class IntfSsoDbTest {

    @Autowired
    private SsoService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String APP = "smk-sso-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        purge();
        asRole("ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        purge();
    }

    private void purge() {
        // 审计日志 hr_change_log 禁止删除（BR-5.6-13 触发器拦截），测试用唯一前缀不干扰即可
        jdbc.update("DELETE FROM erp_intf_sso_app WHERE APP_ID LIKE 'smk-sso-%'");
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("sso-tester", "x", roles));
    }

    private Map<String, Object> register(String appId) {
        return service.registerApp(Map.of(
                "appId", appId, "appName", "冒烟兄弟系统", "audience", "oa-" + appId,
                "redirectUri", "https://oa.example.com/logout"));
    }

    @Test
    void registerAndIssueAssertion() {
        register(APP);
        Map<String, Object> r = service.issueAssertion(APP);
        assertNotNull(r.get("assertion"), "断言已签发");
        assertEquals("oa-" + APP, r.get("audience"), "audience 限定兄弟系统");
        assertEquals(300, r.get("expireSec"), "默认 5 分钟短令牌");
        assertEquals("/api/intf/sso/metadata", r.get("metadataUrl"));
    }

    @Test
    void duplicateAppIdRejected() {
        register(APP);
        ServiceException e = assertThrows(ServiceException.class, () -> register(APP));
        assertEquals(422, e.getCode(), "APP_ID 唯一");
    }

    @Test
    void disabledAppRejected() {
        register(APP);
        service.changeAppStatus(APP, "DISABLED");
        ServiceException e = assertThrows(ServiceException.class, () -> service.issueAssertion(APP));
        assertEquals(422, e.getCode(), "停用拒签");
        assertTrue(e.getMessage().contains("停用"), "提示停用");
    }

    @Test
    void unknownAppRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.issueAssertion("smk-sso-none"));
        assertEquals(422, e.getCode(), "未注册 422");
    }

    @Test
    void registerForbiddenForNonAdmin() {
        asRole("ROLE_INTF_OPS");
        ServiceException e = assertThrows(ServiceException.class, () -> register(APP));
        assertEquals(403, e.getCode(), "注册限 ADMIN");
    }

    @Test
    void metadataReturnsIssuer() {
        Map<String, Object> m = service.metadata();
        assertEquals("erp-idp", m.get("issuer"));
        assertNotNull(m.get("tokenExpireSec"));
    }

    @Test
    void listAppsShowsRegistered() {
        register(APP);
        List<Map<String, Object>> apps = service.listApps();
        assertTrue(apps.stream().anyMatch(a -> APP.equals(a.get("appId"))), "列表含已注册 appId");
    }
}
