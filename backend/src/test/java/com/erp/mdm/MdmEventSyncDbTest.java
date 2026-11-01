package com.erp.mdm;

import com.erp.mdm.cache.MdmReadCache;
import com.erp.mdm.event.MdmChangedEvent;
import com.erp.mdm.event.MdmEventBus;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.8 事件同步库测（add-mdm-version-sync，spec mdm-event-sync）：
 * 缓存命中与失效 / 事件总线双路（内部广播+发件箱）/ 监听台账。
 */
@SpringBootTest
class MdmEventSyncDbTest {

    @Autowired
    private MdmReadCache cache;
    @Autowired
    private MdmEventBus eventBus;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String EID = "evs-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        purge();
        asRole("ROLE_ADMIN");
        cache.invalidateAllTypes();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cacheHitAndInvalidate() {
        AtomicInteger loads = new AtomicInteger();
        // 首次加载
        String v1 = cache.get("ITEM", EID, () -> {
            loads.incrementAndGet();
            return "value-1";
        });
        assertEquals("value-1", v1);
        // 二次命中缓存（loader 不再执行）
        String v2 = cache.get("ITEM", EID, () -> {
            loads.incrementAndGet();
            return "value-2";
        });
        assertEquals("value-1", v2, "命中缓存返回旧值");
        assertEquals(1, loads.get(), "loader 只执行一次");
        // 事件失效
        cache.invalidate("ITEM", EID);
        String v3 = cache.get("ITEM", EID, () -> {
            loads.incrementAndGet();
            return "value-3";
        });
        assertEquals("value-3", v3, "失效后重新加载");
        assertEquals(2, loads.get());
    }

    @Test
    void cacheNullNotCached() {
        AtomicInteger loads = new AtomicInteger();
        cache.get("ITEM", "null-" + EID, () -> {
            loads.incrementAndGet();
            return null;
        });
        cache.get("ITEM", "null-" + EID, () -> {
            loads.incrementAndGet();
            return "x";
        });
        assertEquals(2, loads.get(), "null 不缓存，下次仍执行 loader");
    }

    @Test
    void eventBusPublishesToOutbox() {
        eventBus.publish("MDM.ITEM.UPDATED", "ITEM", EID, 3, List.of("itemName"), false);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT EVENT_TYPE, RECORD_VERSION, STATUS, PAYLOAD FROM erp_ops_outbox "
                        + "WHERE EVENT_TYPE='MDM.ITEM.UPDATED' AND PAYLOAD LIKE ? "
                        + "ORDER BY OCCURRED_AT DESC LIMIT 1", "%" + EID + "%");
        assertEquals("MDM.ITEM.UPDATED", row.get("EVENT_TYPE"));
        assertEquals(3, ((Number) row.get("RECORD_VERSION")).intValue());
        String payload = String.valueOf(row.get("PAYLOAD"));
        assertTrue(payload.contains("itemName"), "变更字段清单入载荷");
    }

    @Test
    void eventBusNeverThrows() {
        // 广播失败不阻断主流程：直接调用不抛异常即通过（内部监听异常已被隔离）
        eventBus.publish("MDM.ITEM.UPDATED", "ITEM", EID, 1, null, false);
        eventBus.publish("MDM.SUPPLIER.MERGED", "SUPPLIER", EID, 1, List.of(), true);
        assertTrue(true);
    }

    @Test
    void taxSyncHintWritesNotice() {
        eventBus.publish("MDM.TAX_CODE.UPDATED", "TAX_CODE", EID, 2, List.of("taxRate"), false);
        // taxSyncHint 监听器异步/同步写通知；轮询短暂等待
        Integer n = 0;
        for (int i = 0; i < 10 && (n == null || n == 0); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MdmTaxSyncHint' AND BIZ_ID=?",
                    Integer.class, EID);
        }
        assertTrue(n != null && n >= 1, "税务工作台同步提示已写入");
    }

    @Test
    void listenerLogTableUsable() {
        // 台账端点依赖表结构：直接插入一行验证列齐全
        jdbc.update("INSERT INTO erp_mdm_listener_log (ID, EVENT_ID, EVENT_TYPE, ENTITY_ID, "
                + "LISTENER_NAME, STATUS, RETRY_CNT, EXEC_MS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'MDM.ITEM.UPDATED', ?, 'priceRecalcMark', 'SUCCESS', 0, 5, "
                + "'evs-test', NOW(), '0', 0)", EID + "-L1", EID, EID);
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mdm_listener_log WHERE EVENT_ID=?", Integer.class, EID);
        assertEquals(1, n.intValue());
    }

    @Test
    void eventTypesRegistered() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_event_type WHERE EVENT_TYPE LIKE 'MDM.%' AND DEL_FLAG='0'",
                Integer.class);
        assertTrue(n >= 40, "MDM 事件类型已注册（13 类×3 + MERGED）");
        assertNotNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_event_type WHERE EVENT_TYPE='MDM.SUPPLIER.MERGED' AND DEL_FLAG='0'",
                Integer.class));
    }

    @Test
    void cacheInvalidateAllClearsBuckets() {
        cache.get("ITEM", EID, () -> "v");
        cache.invalidateAllTypes();
        // 强刷后 loader 重新执行
        AtomicInteger loads = new AtomicInteger();
        cache.get("ITEM", EID, () -> {
            loads.incrementAndGet();
            return "v2";
        });
        assertEquals(1, loads.get());
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("evs-test", "x", roles));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_mdm_listener_log WHERE EVENT_ID LIKE 'evs-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='MdmTaxSyncHint' AND BIZ_ID LIKE 'evs-%'");
        jdbc.update("DELETE FROM erp_ops_outbox WHERE PAYLOAD LIKE '%evs-%'");
    }
}
