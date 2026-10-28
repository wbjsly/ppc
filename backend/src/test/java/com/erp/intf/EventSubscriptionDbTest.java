package com.erp.intf;

import com.erp.common.ServiceException;
import com.erp.entity.intf.IntfDelivery;
import com.erp.service.intf.DeliveryService;
import com.erp.service.intf.EventSubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件订阅 DbTest（add-event-subscription，design D7，spec event-subscription 1.3）：
 * UK 409 / RETRY_MAX 范围 / 订阅路由快照不同 URL / 无订阅回落通道 / 停用回落 /
 * 行级 RETRY_MAX 死信阈值 / 快照语义（订阅改动不影响在途）。造数前缀 SUBTEST。
 */
@SpringBootTest
class EventSubscriptionDbTest {

    @Autowired
    private EventSubscriptionService service;
    @Autowired
    private DeliveryService deliveryService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String PARTNER = "SUBTEST01";

    @BeforeEach
    void setUp() {
        purge();
        asRole("ROLE_INTF_OPS");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    @Test
    void duplicatePartnerTypeRejected() {
        service.create(Map.of("partnerCode", PARTNER, "eventType", "PROC.PO_PUSHED",
                "callbackUrl", "http://a.example.com/cb"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.create(Map.of(
                "partnerCode", PARTNER, "eventType", "PROC.PO_PUSHED")));
        assertEquals(409, e.getCode(), "伙伴×事件类型唯一");
    }

    @Test
    void retryMaxOutOfRangeRejected() {
        ServiceException e = assertThrows(ServiceException.class, () -> service.create(Map.of(
                "partnerCode", PARTNER, "eventType", "PROC.PO_PUSHED", "retryMax", 9)));
        assertEquals(422, e.getCode(), "RETRY_MAX 1~5");
    }

    @Test
    void subscribedEventRoutesToSubscriptionUrl() {
        service.create(Map.of("partnerCode", PARTNER, "eventType", "PROC.PO_PUSHED",
                "callbackUrl", "http://sub.example.com/po", "retryMax", 5));
        String evt = enqueue("PROC.PO_PUSHED", PARTNER);
        deliveryService.scanAndDeliver();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT TARGET_URL, RETRY_MAX FROM erp_intf_delivery WHERE EVENT_ID=?", evt);
        assertEquals("http://sub.example.com/po", row.get("TARGET_URL"), "命中订阅快照 URL");
        assertEquals(5, ((Number) row.get("RETRY_MAX")).intValue(), "行级 RETRY_MAX 快照");
    }

    @Test
    void differentEventsRouteToDifferentUrls() {
        service.create(Map.of("partnerCode", PARTNER, "eventType", "PROC.PO_PUSHED",
                "callbackUrl", "http://a.example.com/po"));
        service.create(Map.of("partnerCode", PARTNER, "eventType", "PROC.ASN_CREATED",
                "callbackUrl", "http://b.example.com/asn"));
        String po = enqueue("PROC.PO_PUSHED", PARTNER);
        String asn = enqueue("PROC.ASN_CREATED", PARTNER);
        deliveryService.scanAndDeliver();
        assertEquals("http://a.example.com/po", urlOf(po));
        assertEquals("http://b.example.com/asn", urlOf(asn), "伙伴×类型独立路由");
    }

    @Test
    void noSubscriptionFallsBackToChannel() {
        // 无订阅记录 → TARGET_URL 留空，attempt 回落通道 URL（与改造前一致）
        String evt = enqueue("PROC.PO_PUSHED", PARTNER);
        deliveryService.scanAndDeliver();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT TARGET_URL, STATUS FROM erp_intf_delivery WHERE EVENT_ID=?", evt);
        assertNull(row.get("TARGET_URL"), "无订阅回落旧路径（零断流）");
        // 通道无 URL → 台账确认投递
        assertEquals(IntfDelivery.ST_DELIVERED, row.get("STATUS"));
    }

    @Test
    void emptyUrlSubscriptionFallsBackToChannel() {
        service.create(Map.of("partnerCode", PARTNER, "eventType", "PROC.PO_PUSHED")); // URL 空
        String evt = enqueue("PROC.PO_PUSHED", PARTNER);
        deliveryService.scanAndDeliver();
        assertNull(jdbc.queryForObject(
                "SELECT TARGET_URL FROM erp_intf_delivery WHERE EVENT_ID=?", String.class, evt),
                "URL 空 = 回落通道（而非中断投递）");
    }

    @Test
    void disabledSubscriptionFallsBackToChannel() {
        Map<String, Object> sub = service.create(Map.of("partnerCode", PARTNER,
                "eventType", "PROC.PO_PUSHED", "callbackUrl", "http://sub.example.com/po"));
        service.toggle(String.valueOf(sub.get("id")), "DISABLED");
        String evt = enqueue("PROC.PO_PUSHED", PARTNER);
        deliveryService.scanAndDeliver();
        assertNull(jdbc.queryForObject(
                "SELECT TARGET_URL FROM erp_intf_delivery WHERE EVENT_ID=?", String.class, evt),
                "停用订阅回落通道 URL（spec 场景）");
    }

    @Test
    void rowLevelRetryMaxDrivesDeadLetter() {
        service.create(Map.of("partnerCode", PARTNER, "eventType", "PROC.PO_PUSHED",
                "callbackUrl", "http://127.0.0.1:1/dead", "retryMax", 5));
        String evt = enqueue("PROC.PO_PUSHED", PARTNER);
        deliveryService.scanAndDeliver();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ID, RETRY_COUNT, RETRY_MAX, STATUS FROM erp_intf_delivery WHERE EVENT_ID=?", evt);
        assertEquals(5, ((Number) row.get("RETRY_MAX")).intValue());
        // 人为推进重试计数至 5：第 6 次失败 > 行级 5 → DEAD（全局默认 3 不适用该订阅）
        jdbc.update("UPDATE erp_intf_delivery SET RETRY_COUNT=5, STATUS='RETRYING', NEXT_AT=? WHERE ID=?",
                LocalDateTime.now().minusSeconds(1), row.get("ID"));
        deliveryService.scanAndDeliver();
        assertEquals("DEAD", jdbc.queryForObject(
                "SELECT STATUS FROM erp_intf_delivery WHERE ID=?", String.class, row.get("ID")),
                "行级 RETRY_MAX=5 才转死信");
    }

    @Test
    void snapshotIsolatedFromLaterSubscriptionChange() {
        Map<String, Object> sub = service.create(Map.of("partnerCode", PARTNER,
                "eventType", "PROC.PO_PUSHED", "callbackUrl", "http://old.example.com/cb"));
        String evt = enqueue("PROC.PO_PUSHED", PARTNER);
        deliveryService.scanAndDeliver(); // 建行快照 old URL
        service.update(String.valueOf(sub.get("id")), Map.of("callbackUrl", "http://new.example.com/cb"));
        assertEquals("http://old.example.com/cb", urlOf(evt),
                "快照语义：订阅改动不影响在途投递（防半投递）");
        // 新事件走新 URL（同伙伴同类型，第二条事件）
        String evt2 = enqueue("PROC.PO_PUSHED", PARTNER);
        deliveryService.scanAndDeliver();
        assertEquals("http://new.example.com/cb", urlOf(evt2));
    }

    // ---------------------------------------------------------------- helpers

    private String urlOf(String eventId) {
        return jdbc.queryForObject("SELECT TARGET_URL FROM erp_intf_delivery WHERE EVENT_ID=?",
                String.class, eventId);
    }

    /** 直插 outbox 造事件（payload 带 partnerCode 供路由抠取） */
    private String enqueue(String eventType, String partnerCode) {
        String evtId = "subtest-evt-" + UUID.randomUUID();
        String key = "SUBTEST:" + evtId + ":v1";
        jdbc.update("INSERT INTO erp_ops_outbox (ID, EVENT_ID, EVENT_TYPE, VERSION, RECORD_VERSION, " +
                        "OCCURRED_AT, SOURCE, IDEMPOTENCY_KEY, PAYLOAD, STATUS, DEL_FLAG, VER_NO) " +
                        "VALUES (?, ?, ?, 1, 1, ?, 'test', ?, ?, 'PENDING', '0', 0)",
                UUID.randomUUID().toString(), evtId, eventType, LocalDateTime.now(), key,
                "{\"partnerCode\":\"" + partnerCode + "\",\"bizCode\":\"SUBTEST\"}");
        return evtId;
    }

    private void purge() {
        jdbc.update("DELETE d FROM erp_intf_delivery d WHERE d.PARTNER_CODE LIKE 'SUBTEST%'");
        jdbc.update("DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'SUBTEST:%'");
        jdbc.update("DELETE FROM erp_intf_subscription WHERE PARTNER_CODE LIKE 'SUBTEST%'");
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "sub-tester", "x", roles));
    }
}
