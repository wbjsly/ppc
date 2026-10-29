package com.erp.intf;

import com.erp.controller.IntfConsoleController;
import com.erp.entity.intf.IntfTierChangeLog;
import com.erp.service.intf.GatewayDecision;
import com.erp.service.intf.TierChangeLogService;
import com.erp.service.impl.intf.OpenApiGatewayServiceImpl;
import com.erp.service.scm.PartnerChannelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档位变更历史 DbTest（add-interface-call-console，design D1，spec 12.3.2）：
 * 三处写入点（自动降档 / 2.8.3 手动调档 / 12.1.3 手动调档）同源落历史行，
 * 且历史只增不改不删（任务 2.2 / 2.3）。造数前缀 ICC。
 */
@SpringBootTest
class IntfCallConsoleDbTest {

    private static final String PARTNER = "ICC-P01";
    private static final String CALLER = "ICC-KEY-01";

    @Autowired
    private TierChangeLogService tierChangeLog;
    @Autowired
    private PartnerChannelService partnerChannelService;
    @Autowired
    private IntfConsoleController consoleController;
    @Autowired
    private com.erp.controller.IntfCallConsoleController callConsole;
    @Autowired
    private OpenApiGatewayServiceImpl gateway;
    @Autowired
    private JdbcTemplate jdbc;

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

    /** 2.2-① 自动降档写入点（网关 downgrade 私有方法，BR-4.9-19） */
    @Test
    void autoDowngradeWritesHistoryRow() {
        String channelId = createChannelWithTier("NORMAL");
        String credId = insertCredential(channelId, "NORMAL", 100);

        com.erp.entity.intf.IntfCredential cred = jdbc.queryForObject(
                "SELECT * FROM erp_intf_credential WHERE ID=?", (rs, i) -> {
                    com.erp.entity.intf.IntfCredential c = new com.erp.entity.intf.IntfCredential();
                    c.setId(rs.getString("ID"));
                    c.setRateTier(rs.getString("RATE_TIER"));
                    c.setLimitPerMin(rs.getInt("LIMIT_PER_MIN"));
                    return c;
                }, credId);
        com.erp.entity.intf.IntfChannel ch = jdbc.queryForObject(
                "SELECT * FROM erp_intf_channel WHERE ID=?", (rs, i) -> {
                    com.erp.entity.intf.IntfChannel c = new com.erp.entity.intf.IntfChannel();
                    c.setId(rs.getString("ID"));
                    c.setPartnerCode(rs.getString("PARTNER_CODE"));
                    return c;
                }, channelId);
        GatewayDecision d = new GatewayDecision();
        d.setCaller(CALLER);

        ReflectionTestUtils.invokeMethod(gateway, "downgrade", cred, ch, d);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT CHANGE_TYPE, TIER_FROM, TIER_TO, LIMIT_FROM, LIMIT_TO, OPERATOR, PARTNER_CODE " +
                        "FROM erp_intf_tier_change_log WHERE CALLER=? AND CHANGE_TYPE='AUTO_DOWNGRADE'", CALLER);
        assertEquals("NORMAL", row.get("TIER_FROM"));
        assertEquals("LOWEST", row.get("TIER_TO"));
        assertEquals(100, ((Number) row.get("LIMIT_FROM")).intValue());
        assertEquals(30, ((Number) row.get("LIMIT_TO")).intValue());
        assertEquals(IntfTierChangeLog.OPERATOR_SYSTEM, row.get("OPERATOR"));
        assertEquals(PARTNER, row.get("PARTNER_CODE"));
        // 既有 downgradeFrom 断言不被破坏（channel 侧留痕仍在）
        assertEquals("NORMAL", jdbc.queryForObject(
                "SELECT DOWNGRADE_FROM FROM erp_intf_channel WHERE ID=?", String.class, channelId));
    }

    /** 2.2-② 12.1.3 手动调档写入点（PartnerChannelService.updateRateTier） */
    @Test
    void manualTierChangeFromPartnerChannelWritesHistory() {
        String channelId = createChannelWithTier("NEW");
        partnerChannelService.updateRateTier(channelId, "STRATEGIC");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT CHANGE_TYPE, TIER_FROM, TIER_TO, LIMIT_FROM, LIMIT_TO, OPERATOR, TRIGGER_REASON " +
                        "FROM erp_intf_tier_change_log WHERE CALLER=? AND CHANGE_TYPE='MANUAL'", PARTNER);
        assertEquals("NEW", row.get("TIER_FROM"));
        assertEquals("STRATEGIC", row.get("TIER_TO"));
        assertEquals(30, ((Number) row.get("LIMIT_FROM")).intValue());
        assertEquals(500, ((Number) row.get("LIMIT_TO")).intValue());
        assertEquals("icc-tester", row.get("OPERATOR"));
        assertTrue(String.valueOf(row.get("TRIGGER_REASON")).contains("12.1.3"));
    }

    /** 2.2-③ 2.8.3 手动调档写入点（IntfConsoleController.rateTier） */
    @Test
    void manualTierChangeFromConsoleWritesHistory() {
        String channelId = createChannelWithTier("STRATEGIC");
        asRole("ROLE_ADMIN");
        consoleController.rateTier(channelId, Map.of("tier", "NORMAL"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT CHANGE_TYPE, TIER_FROM, TIER_TO, LIMIT_FROM, LIMIT_TO, OPERATOR, TRIGGER_REASON " +
                        "FROM erp_intf_tier_change_log WHERE CALLER=? AND CHANGE_TYPE='MANUAL'", PARTNER);
        assertEquals("STRATEGIC", row.get("TIER_FROM"));
        assertEquals("NORMAL", row.get("TIER_TO"));
        assertEquals(500, ((Number) row.get("LIMIT_FROM")).intValue());
        assertEquals(100, ((Number) row.get("LIMIT_TO")).intValue());
        assertEquals("icc-tester", row.get("OPERATOR"));
        assertTrue(String.valueOf(row.get("TRIGGER_REASON")).contains("2.8.3"));
    }

    /** 2.3 历史只增不改不删：服务接口无 update/delete 方法，查询前后行数不变 */
    @Test
    void historyIsImmutableAndReadOnly() {
        String channelId = createChannelWithTier("NEW");
        partnerChannelService.updateRateTier(channelId, "NORMAL");
        long before = countHistory();

        // 查询操作不改变行数
        tierChangeLog.history(PARTNER, 1, 10);
        tierChangeLog.history(null, 1, 10);
        assertEquals(before, countHistory(), "查询不得改变历史行数");

        // 接口只暴露 record + history：无 update/delete/insert(除 record 内部) 方法
        for (Method m : TierChangeLogService.class.getMethods()) {
            String n = m.getName().toLowerCase();
            assertTrue(n.contains("record") || n.contains("history") || n.equals("equals")
                            || n.equals("hashcode") || n.equals("tostring"),
                    "TierChangeLogService 不得暴露改删方法: " + m.getName());
        }
        assertNotNull(rowId());
    }

    private long countHistory() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_intf_tier_change_log WHERE CALLER IN (?,?)",
                Long.class, CALLER, PARTNER);
    }

    // ==================== 3.1~3.5 查询端点 ====================

    /** 3.1：四类可观测拒绝原因分组 + 明细下钻（spec 12.3.1 两场景） */
    @Test
    void authRejectionGroupsAndDrillDown() {
        insertCall("ICC-R-1", "/api/open/ping", "ICC-REQ-400", 400, "INVALID_ARGUMENT", 1, now().minusMinutes(10));
        insertCall("ICC-R-1", "/api/open/ping", "ICC-REQ-401S", 401, "SECURITY_EVENT:HMAC 签名不匹配", 1, now().minusMinutes(9));
        insertCall("ICC-R-1", "/api/open/ping", "ICC-REQ-401U", 401, "UNAUTHENTICATED", 1, now().minusMinutes(8));
        insertCall("ICC-R-1", "/api/open/ping", "ICC-REQ-403", 403, null, 1, now().minusMinutes(7));
        insertCall("ICC-R-1", "/api/open/ping", "ICC-REQ-200", 200, null, 5, now().minusMinutes(6)); // 非拒绝，不入组

        // 按 CALLER 过滤断言（库内可能有冒烟/其它测试残留的 4xx 行，跨调用方求和会被污染）
        var resp = callConsole.authRejections("ICC-R-1", null, null, null, 1, 50);
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) resp.getData();
        @SuppressWarnings("unchecked")
        var groups = (java.util.List<java.util.Map<String, Object>>) data.get("groups");
        long sig = groups.stream().filter(g -> "SECURITY_EVENT".equals(g.get("reason"))
                && "ICC-R-1".equals(g.get("caller"))).mapToLong(g -> ((Number) g.get("total")).longValue()).sum();
        assertEquals(1, sig, "签名与口令安全事件独立成组（B' 修复后 SECURITY_EVENT 存活）");
        long ts = groups.stream().filter(g -> "TIMESTAMP_SKEW".equals(g.get("reason"))
                && "ICC-R-1".equals(g.get("caller"))).mapToLong(g -> ((Number) g.get("total")).longValue()).sum();
        assertEquals(1, ts, "时间戳超差（400）成组");
        long scope = groups.stream().filter(g -> "SCOPE_DENIED".equals(g.get("reason"))
                && "ICC-R-1".equals(g.get("caller"))).mapToLong(g -> ((Number) g.get("total")).longValue()).sum();
        assertEquals(1, scope, "Scope 拒绝（403）成组");
        long cred = groups.stream().filter(g -> "CREDENTIAL_AUTH_FAILED".equals(g.get("reason"))
                && "ICC-R-1".equals(g.get("caller"))).mapToLong(g -> ((Number) g.get("total")).longValue()).sum();
        assertEquals(1, cred, "凭证认证失败（其余 401）成组");

        @SuppressWarnings("unchecked")
        var detail = (com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.erp.entity.intf.IntfCallLog>) data.get("detail");
        assertEquals(4, detail.getTotal(), "明细总数 = 四组之和（2xx 不入）");

        // 下钻：SECURITY_EVENT 过滤后仅安全事件行
        var drill = callConsole.authRejections("ICC-R-1", null, null, "SECURITY_EVENT", 1, 50);
        @SuppressWarnings("unchecked")
        var drillDetail = (com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.erp.entity.intf.IntfCallLog>)
                ((java.util.Map<String, Object>) drill.getData()).get("detail");
        assertEquals(1, drillDetail.getTotal());
        assertTrue(drillDetail.getRecords().get(0).getErrCode().startsWith("SECURITY_EVENT"));
    }

    /** 3.3：429 趋势总数 = 同条件明细行数（spec 12.3.2 对账场景） */
    @Test
    void rateTrendReconcilesWithDetail() {
        for (int i = 1; i <= 5; i++) {
            insertCall("ICC-TREND", "/api/open/ping", "ICC-REQ-429-" + i, 429, "RATE_LIMITED", 2,
                    now().minusMinutes(i * 3));
        }
        insertCall("ICC-TREND", "/api/open/ping", "ICC-REQ-429-OLD", 429, "RATE_LIMITED", 2,
                now().minusDays(3)); // 窗口外

        var resp = callConsole.rateTrend(24, null);
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) resp.getData();
        long total = ((Number) data.get("total")).longValue();
        @SuppressWarnings("unchecked")
        var buckets = (java.util.List<java.util.Map<String, Object>>) data.get("buckets");
        long bucketSum = buckets.stream().mapToLong(b -> ((Number) b.get("total")).longValue()).sum();
        assertEquals(5, total, "近 24h 429 总数（窗口外不计）");
        assertEquals(total, bucketSum, "分桶合计 = 总数");

        long detailCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_call_log WHERE RESP_CODE=429 AND CALLER='ICC-TREND' " +
                        "AND CALL_AT >= ?", Long.class, java.time.LocalDateTime.now().minusHours(24));
        assertEquals(detailCount, total, "趋势总数 = 同条件明细行数");
    }

    /** 3.4a：Trace 下钻含被拒请求（spec 12.3.3 场景） */
    @Test
    void traceIncludesRejectedRequest() {
        insertCall("ICC-TR", "/api/open/orders", "ICC-REQ-TRACE", 401, "UNAUTHENTICATED", 1, now().minusMinutes(2));
        var rows = callConsole.trace("ICC-REQ-TRACE").getData();
        assertEquals(1, rows.size());
        assertEquals(401, rows.get(0).getRespCode(), "被拒请求同样可追溯");
        assertEquals("ICC-TR", rows.get(0).getCaller());
    }

    /** 3.4b：审计聚合 调用量/成功率/4xx/5xx/P95（spec 12.3.3） */
    @Test
    void auditAggregatesCountsAndP95() {
        // 时间边界防护：聚合窗口从"当天 0 点"起算，造数锚定当天 10:00（避免午夜附近 now()-minusMinutes 跨日出窗）
        java.time.LocalDateTime anchor = java.time.LocalDate.now().atTime(10, 0);
        for (int i = 0; i < 20; i++) {
            insertCall("ICC-AGG", "/api/open/ping", "ICC-REQ-AGG-OK-" + i, 200, null, 100, anchor.minusMinutes(i + 1));
        }
        for (int i = 0; i < 2; i++) {
            insertCall("ICC-AGG", "/api/open/ping", "ICC-REQ-AGG-ERR-" + i, 500, null, 200, anchor.minusMinutes(i + 1));
        }
        insertCall("ICC-AGG", "/api/open/ping", "ICC-REQ-AGG-4XX", 404, null, 50, anchor.minusMinutes(25));

        var rows = callConsole.auditAggregates(
                java.time.LocalDate.now(), java.time.LocalDate.now(), "ICC-AGG").getData();
        assertEquals(1, rows.size(), "同调用方×接口×日聚合为一行");
        var row = rows.get(0);
        assertEquals(23, ((Number) row.get("total")).longValue());
        assertEquals(20, ((Number) row.get("success")).longValue());
        assertEquals(1, ((Number) row.get("e4xx")).longValue());
        assertEquals(2, ((Number) row.get("e5xx")).longValue());
        // ceil(23*0.95)=22 → 第 22 小耗时；排序后 22 位落在 200 组内
        assertEquals(200, ((Number) row.get("p95")).intValue(), "P95 按组内第 ceil(n*0.95) 小耗时");
    }

    /** 3.2：状态总览（通道档位/桶容量/熔断）+ 档位变更历史（自动/手动同源可区分，spec 12.3.2） */
    @Test
    void rateOverviewAndTierChangesEndpoints() {
        String channelId = createChannelWithTier("NORMAL");
        // 手动（12.1.3 写入点）
        partnerChannelService.updateRateTier(channelId, "STRATEGIC");
        // 自动（网关 downgrade 写入点）
        String credId = insertCredential(channelId, "NORMAL", 100);
        com.erp.entity.intf.IntfCredential cred = new com.erp.entity.intf.IntfCredential();
        cred.setId(credId);
        cred.setRateTier("NORMAL");
        cred.setLimitPerMin(100);
        com.erp.entity.intf.IntfChannel ch = new com.erp.entity.intf.IntfChannel();
        ch.setId(channelId);
        ch.setPartnerCode(PARTNER);
        GatewayDecision d = new GatewayDecision();
        d.setCaller(CALLER);
        ReflectionTestUtils.invokeMethod(gateway, "downgrade", cred, ch, d);

        // 状态总览
        @SuppressWarnings("unchecked")
        var overview = (java.util.Map<String, Object>) callConsole.rateOverview().getData();
        @SuppressWarnings("unchecked")
        var channels = (java.util.List<java.util.Map<String, Object>>) overview.get("channels");
        var mine = channels.stream().filter(c -> PARTNER.equals(c.get("partnerCode"))).findFirst();
        assertTrue(mine.isPresent(), "总览含本通道");
        assertEquals(500, ((Number) mine.get().get("limitPerMin")).intValue());
        assertEquals(750, ((Number) mine.get().get("bucketCapacity")).intValue(), "桶容量 = 限值 × 1.5");
        assertNotNull(mine.get().get("circuitState"));
        assertTrue(overview.get("windows") instanceof java.util.List, "含最新统计窗口");

        // 变更历史：自动与手动同源返回且可区分
        @SuppressWarnings("unchecked")
        var changes = (java.util.Map<String, Object>) callConsole.tierChanges(null, 1, 50).getData();
        @SuppressWarnings("unchecked")
        var records = (java.util.List<com.erp.entity.intf.IntfTierChangeLog>) changes.get("records");
        assertTrue(records.stream().anyMatch(r -> com.erp.entity.intf.IntfTierChangeLog.TYPE_AUTO
                        .equals(r.getChangeType()) && com.erp.entity.intf.IntfTierChangeLog.OPERATOR_SYSTEM.equals(r.getOperator())),
                "自动降档行（SYSTEM）存在");
        assertTrue(records.stream().anyMatch(r -> com.erp.entity.intf.IntfTierChangeLog.TYPE_MANUAL
                        .equals(r.getChangeType()) && "icc-tester".equals(r.getOperator())),
                "手动调档行（操作者）存在");
        assertTrue(((Number) changes.get("total")).longValue() >= 2);
    }

    /** 3.5：call-logs 状态码分组筛选互斥且与全量可对账 */
    @Test
    void callLogsRespGroupFilterIsExclusive() {
        insertCall("ICC-GRP", "/api/open/ping", "ICC-REQ-G-2XX", 200, null, 5, now().minusMinutes(3));
        insertCall("ICC-GRP", "/api/open/ping", "ICC-REQ-G-4XX", 401, "UNAUTHENTICATED", 1, now().minusMinutes(2));
        insertCall("ICC-GRP", "/api/open/ping", "ICC-REQ-G-5XX", 503, "CIRCUIT_OPEN", 1, now().minusMinutes(1));

        long all = consoleController.callLogs(1, 50, "ICC-GRP", null, null).getData().getTotal();
        long g2 = consoleController.callLogs(1, 50, "ICC-GRP", null, "2xx").getData().getTotal();
        long g4 = consoleController.callLogs(1, 50, "ICC-GRP", null, "4xx").getData().getTotal();
        long g5 = consoleController.callLogs(1, 50, "ICC-GRP", null, "5xx").getData().getTotal();
        assertEquals(3, all);
        assertEquals(1, g2);
        assertEquals(1, g4);
        assertEquals(1, g5);
        assertEquals(all, g2 + g4 + g5, "三组互斥且与全量对账");
    }

    private static java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now();
    }

    /** 直插审计行（只读查询造数，前缀 ICC-） */
    private void insertCall(String caller, String path, String reqId, int respCode,
                            String errCode, int costMs, java.time.LocalDateTime callAt) {
        jdbc.update("INSERT INTO erp_intf_call_log " +
                        "(ID, CALLER, API_PATH, HTTP_METHOD, REQ_ID, RESP_CODE, ERR_CODE, COST_MS, " +
                        "RATE_HIT, CIRCUIT_HIT, ENV, CALL_AT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,?,?,?,?,?,0,0,'SANDBOX',?, 'smoke',?, '0',0)",
                "icc-" + reqId.toLowerCase(), caller, path, "POST", reqId, respCode, errCode, costMs,
                callAt, callAt);
    }

    private String rowId() {
        return jdbc.queryForObject(
                "SELECT ID FROM erp_intf_tier_change_log WHERE CALLER=? LIMIT 1", String.class, PARTNER);
    }

    private String createChannelWithTier(String tier) {
        Map<String, Object> out = partnerChannelService.createChannel(Map.of(
                "partnerCode", PARTNER, "partnerName", "ICC 测试伙伴",
                "channelType", "API", "protocol", "HTTPS"));
        Object ch = out.get("channel");
        String id = ch instanceof com.erp.entity.intf.IntfChannel c
                ? c.getId() : String.valueOf(((Map<?, ?>) ch).get("id"));
        jdbc.update("UPDATE erp_intf_channel SET RATE_TIER=?, LIMIT_PER_MIN=? WHERE ID=?",
                tier, "STRATEGIC".equals(tier) ? 500 : "NORMAL".equals(tier) ? 100 : 30, id);
        return id;
    }

    private String insertCredential(String channelId, String tier, int limit) {
        String id = "icc-cred-1";
        jdbc.update("INSERT INTO erp_intf_credential " +
                        "(ID, CHANNEL_ID, PARTNER_CODE, API_KEY, SECRET_HASH, SECRET_SALT, SIGN_KEY, " +
                        "ENV, RATE_TIER, LIMIT_PER_MIN, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?, 'ACTIVE','smoke',NOW(),'0',0)",
                id, channelId, PARTNER, CALLER, "hash", "salt", "sign",
                "SANDBOX", tier, limit);
        return id;
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_intf_tier_change_log WHERE CALLER IN (?,?)", CALLER, PARTNER);
        jdbc.update("DELETE FROM erp_intf_credential WHERE PARTNER_CODE=?", PARTNER);
        jdbc.update("DELETE FROM erp_intf_channel WHERE PARTNER_CODE=?", PARTNER);
        jdbc.update("DELETE FROM erp_intf_call_log WHERE CALLER LIKE 'ICC-%' OR REQ_ID LIKE 'ICC-REQ-%'");
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "icc-tester", "x", roles));
    }
}
