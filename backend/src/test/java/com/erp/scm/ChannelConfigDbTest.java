package com.erp.scm;

import com.erp.common.ServiceException;
import com.erp.service.scm.PartnerChannelService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伙伴通道配置 DbTest（add-partner-onboarding，design D5，spec partner-onboarding 1.6）：
 * 通道创建（SANDBOX 默认/重复 409）、密钥签发哈希落库、限流档位、证书更新、INTF_OPS 限权。
 * 造数前缀 pch-。
 */
@SpringBootTest
class ChannelConfigDbTest {

    @Autowired
    private PartnerChannelService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String PARTNER = "PCH001";

    @BeforeEach
    void setUp() {
        purge();
        asIntfOps();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    @Test
    void createChannelDefaultsToSandboxWithNewTier() {
        Map<String, Object> out = service.createChannel(Map.of(
                "partnerCode", PARTNER, "partnerName", "PCH 测试伙伴",
                "channelType", "API", "protocol", "HTTPS",
                "callbackUrl", "https://partner.example.com/cb"));
        String id = String.valueOf(idOf(out.get("channel")));
        // 落库断言：SANDBOX 默认 + NEW 档 30 次/分钟（BR-4.9-18）
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT STATUS, RATE_TIER, LIMIT_PER_MIN, CIRCUIT_STATE FROM erp_intf_channel WHERE ID=?", id);
        assertEquals("SANDBOX", row.get("STATUS"), "新建默认 SANDBOX（PROD 走放行会签）");
        assertEquals("NEW", row.get("RATE_TIER"));
        assertEquals(30, ((Number) row.get("LIMIT_PER_MIN")).intValue());
        assertEquals("CLOSED", row.get("CIRCUIT_STATE"));
    }

    @Test
    void duplicatePartnerProtocolRejected() {
        service.createChannel(Map.of("partnerCode", PARTNER, "partnerName", "PCH 测试伙伴",
                "channelType", "API", "protocol", "HTTPS"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.createChannel(Map.of("partnerCode", PARTNER,
                        "partnerName", "PCH 测试伙伴", "channelType", "API", "protocol", "HTTPS")));
        assertEquals(409, e.getCode());
    }

    @Test
    void generateKeyStoresHashNotPlaintext() {
        String channelId = createOne();
        Map<String, Object> out = service.generateKey(Map.of(
                "channelId", channelId, "scope", "po:read,asn:write", "ttlDays", 90));
        // 明文仅本次返回
        assertNotNull(out.get("apiKey"), "明文 apiKey 本次返回");
        assertNotNull(out.get("secret"), "明文 secret 本次返回");
        // 落库为哈希+盐，非明文
        Map<String, Object> cred = jdbc.queryForMap(
                "SELECT API_KEY, SECRET_HASH, SECRET_SALT, ENV, RATE_TIER FROM erp_intf_credential "
                        + "WHERE CHANNEL_ID = ? ORDER BY ISSUE_AT DESC LIMIT 1", channelId);
        assertNotNull(cred.get("SECRET_HASH"));
        assertTrue(String.valueOf(cred.get("SECRET_HASH")).length() > 20, "落库为哈希");
        // 明文 secret 不落库（与哈希不同）
        assertTrue(!String.valueOf(cred.get("SECRET_HASH")).equals(String.valueOf(out.get("secret"))),
                "secret 落库为加盐哈希非明文");
        assertEquals("SANDBOX", cred.get("ENV"), "沙箱凭证环境隔离");
    }

    @Test
    void updateRateTierChangesLimit() {
        String channelId = createOne();
        service.updateRateTier(channelId, "STRATEGIC");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT RATE_TIER, LIMIT_PER_MIN FROM erp_intf_channel WHERE ID=?", channelId);
        assertEquals("STRATEGIC", row.get("RATE_TIER"));
        assertEquals(500, ((Number) row.get("LIMIT_PER_MIN")).intValue());
        // 非法档位 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.updateRateTier(channelId, "GOLD"));
        assertEquals(422, e.getCode());
    }

    @Test
    void updateCertRejectsExpiredDate() {
        String channelId = createOne();
        service.updateCert(channelId, Map.of(
                "certFingerprint", "AA:BB:CC:DD", "certExpireDate", "2099-12-31"));
        assertEquals("AA:BB:CC:DD", jdbc.queryForObject(
                "SELECT CERT_FINGERPRINT FROM erp_intf_channel WHERE ID=?", String.class, channelId));
        // 过期日期 422（BR-4.9-22 前置）
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.updateCert(channelId, Map.of("certFingerprint", "XX",
                        "certExpireDate", "2020-01-01")));
        assertEquals(422, e.getCode());
    }

    @Test
    void procurementRoleForbidden() {
        asRole("ROLE_PROCUREMENT");
        ServiceException e = assertThrows(ServiceException.class, () -> service.list(null));
        assertEquals(403, e.getCode(), "配置页非 INTF_OPS/ADMIN 拒绝");
    }

    @Test
    void listReturnsCreatedChannels() {
        createOne();
        List<Map<String, Object>> list = service.list(PARTNER);
        assertEquals(1, list.size());
        assertEquals(PARTNER, list.get(0).get("partnerCode"));
    }

    // ==================== 造数辅助 ====================

    private String createOne() {
        Map<String, Object> out = service.createChannel(Map.of(
                "partnerCode", PARTNER, "partnerName", "PCH 测试伙伴",
                "channelType", "API", "protocol", "HTTPS"));
        return String.valueOf(idOf(out.get("channel")));
    }

    /** createChannel/generateKey 返回 {channel: IntfChannel 实体} */
    private static Object idOf(Object ch) {
        if (ch instanceof com.erp.entity.intf.IntfChannel c) {
            return c.getId();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) ch;
        return m.get("id");
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_intf_credential WHERE PARTNER_CODE = ?", PARTNER);
        jdbc.update("DELETE FROM erp_intf_channel WHERE PARTNER_CODE = ?", PARTNER);
    }

    private void asIntfOps() {
        asRole("ROLE_INTF_OPS");
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "pch-tester", "x", roles));
    }
}
