package com.erp.intf;

import com.erp.common.ServiceException;
import com.erp.service.intf.SlaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SLA 服务监控增量（add-service-monitoring-console，spec interface-sla-monitoring delta +
 * service-monitoring-console）：
 * - 伙伴维度指标行（活跃有行 / 零活动无行 / 伙伴行不产告警）
 * - 报告章节 METRICS_JSON={overview, chapters[]} 与 unreach partnerCode
 * - 生成人互斥（记操作人→本人审核 422→他人通过）、审核角色 OPS 403、
 *   未归档发布 422+违规留痕、GENERATED 直接归档 422
 * 造数前缀 SLAC / 月份 209902。
 */
@SpringBootTest
class SlaConsoleDbTest {

    private static final String MONTH = "209902";
    private static final String PARTNER = "slac-partner";

    @Autowired
    private SlaService service;
    @Autowired
    private JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        purge();
        asRole("ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    // ==================== 2.2 伙伴维度指标行 ====================

    @Test
    void partnerRowsWrittenAndNoPartnerAlerts() {
        // 20 笔全局快调用（caller=other）+ 1 笔伙伴慢调用（600ms > P95 目标 500）
        // → 全局 P95≈5ms OK、伙伴 P95=600ms CRITICAL → 伙伴行越阈但不产告警
        for (int i = 0; i < 20; i++) {
            insertCall("other-caller", "SLACT-G" + i, 5, 200);
        }
        insertCall(PARTNER, "SLACT-P1", 600, 200);

        service.collect();

        String level = jdbc.queryForObject(
                "SELECT LEVEL FROM erp_intf_sla_metric WHERE PARTNER_CODE=? AND METRIC_KEY='P95' " +
                        "ORDER BY WINDOW_START DESC LIMIT 1", String.class, PARTNER);
        assertEquals("CRITICAL", level, "伙伴 P95 行按同口径判定越阈");
        // 伙伴行不触发告警：不存在实际值=600（伙伴专属）的 P95 告警
        // （按实际值断言以隔离 8090 应用调度器并发产生的全局告警）
        Integer partnerAlerts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_sla_alert WHERE METRIC_KEY='P95' AND ACTUAL_VALUE=600",
                Integer.class);
        assertEquals(0, partnerAlerts, "告警仅挂全局行（design D2）");
        // 零活动伙伴不写行（无样本 ≠ 数据缺失）
        Integer noneRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_sla_metric WHERE PARTNER_CODE='slac-none'",
                Integer.class);
        assertEquals(0, noneRows, "零活动伙伴不写行");
        // 全局行照常
        Integer globalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_sla_metric WHERE PARTNER_CODE IS NULL " +
                        "AND MONTH_TAG=DATE_FORMAT(NOW(), '%Y%m')", Integer.class);
        assertTrue(globalRows >= 3, "全局四项指标行照常写入");
    }

    // ==================== 2.3 章节产出 ====================

    @Test
    void generateBuildsChaptersAndPartnerUnreach() {
        // 直插伙伴/全局指标行（含一条伙伴 WARNING 供章节 unreach）
        insertMetric(PARTNER, "AVAILABILITY", "99.20", "OK", MONTH);
        insertMetric(PARTNER, "P95", "880.0000", "WARNING", MONTH);
        insertMetric(null, "AVAILABILITY", "99.9500", "OK", MONTH);
        insertMetric(null, "P95", "610.0000", "WARNING", MONTH);

        Map<String, Object> out = service.generateReport(MONTH, "gen-user");
        assertEquals("GENERATED", out.get("status"));

        String json = jdbc.queryForObject(
                "SELECT METRICS_JSON FROM erp_intf_sla_report WHERE MONTH_TAG=?", String.class, MONTH);
        JsonNode root = read(json);
        assertTrue(root.has("overview"), "METRICS_JSON 含 overview（D1 形状）");
        assertTrue(root.get("overview").has("AVAILABILITY"), "overview 含全局指标计数");
        JsonNode chapters = root.get("chapters");
        assertTrue(chapters.isArray() && chapters.size() >= 1, "chapters 数组含活跃伙伴");
        JsonNode ch = null;
        for (JsonNode c : chapters) {
            if (PARTNER.equals(c.path("partnerCode").asText())) {
                ch = c;
            }
        }
        assertNotNull(ch, "章节含该伙伴");
        assertTrue(ch.get("metrics").has("P95"), "伙伴章节含指标聚合");
        assertEquals(1, ch.get("unreach").size(), "伙伴未达标子项入章节");
        assertEquals("WARNING", ch.get("unreach").get(0).path("level").asText());
        assertEquals(PARTNER, ch.get("unreach").get(0).path("partnerCode").asText(),
                "章节 unreach 带 partnerCode");

        String unreach = jdbc.queryForObject(
                "SELECT UNREACHED_JSON FROM erp_intf_sla_report WHERE MONTH_TAG=?", String.class, MONTH);
        assertTrue(unreach.contains("\"partnerCode\""), "全局 unreach 子项补 partnerCode 字段");
        // 总览不重复计数：仅统计全局行（2 条全局 OK）
        assertEquals(2, root.get("overview").get("AVAILABILITY").asInt()
                        + root.get("overview").get("P95").asInt(),
                "overview 只计全局行（伙伴行不混入）");
    }

    // ==================== 2.4 互斥与状态机 ====================

    @Test
    void reviewRoleMutexAndStateMachine() {
        // 手动生成记操作人
        service.generateReport(MONTH, "arch-user");
        String id = jdbc.queryForObject(
                "SELECT ID FROM erp_intf_sla_report WHERE MONTH_TAG=?", String.class, MONTH);
        assertEquals("arch-user", jdbc.queryForObject(
                "SELECT GENERATED_BY FROM erp_intf_sla_report WHERE ID=?", String.class, id));

        // GENERATED 直接归档 → 422
        asRole("ROLE_ADMIN");
        ServiceException arc = assertThrows(ServiceException.class, () -> service.archiveReport(id));
        assertEquals(422, arc.getCode());

        // 未归档发布 → 422 + 违规留痕
        ServiceException pub = assertThrows(ServiceException.class, () -> service.publishReport(id, "ALL"));
        assertEquals(422, pub.getCode());
        Integer violations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_sla_alert WHERE ALERT_KEY LIKE 'SLA_PUBLISH_BLOCK:%'",
                Integer.class);
        assertTrue(violations >= 1, "违规尝试留痕（C-5.5-04）");

        // OPS 审核 → 403（角色强制）
        asRole("ROLE_INTF_OPS");
        ServiceException ops = assertThrows(ServiceException.class,
                () -> service.reviewReport(id, "复核意见"));
        assertEquals(403, ops.getCode());

        // 生成人本人审核 → 422（互斥对人类生效）
        asRole("ROLE_INTF_ARCH");
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("arch-user", "x", "ROLE_INTF_ARCH"));
        ServiceException self = assertThrows(ServiceException.class,
                () -> service.reviewReport(id, "自己审自己"));
        assertEquals(422, self.getCode());

        // 换另一架构师 → 200 REVIEWED
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("arch2-user", "x", "ROLE_INTF_ARCH"));
        Map<String, Object> ok = service.reviewReport(id, "复核通过，同意归档");
        assertEquals("REVIEWED", ok.get("status"));

        // 归档（ADMIN）→ ARCHIVED + 版本/时间戳
        asRole("ROLE_ADMIN");
        Map<String, Object> archived = service.archiveReport(id);
        assertEquals("ARCHIVED", archived.get("status"));
        assertNotNull(archived.get("version"));
        assertNotNull(archived.get("archivedAt"));
    }

    // ==================== 帮助方法 ====================

    private void insertCall(String caller, String reqId, int costMs, int resp) {
        jdbc.update("INSERT INTO erp_intf_call_log (ID,CALLER,API_PATH,REQ_ID,RESP_CODE,COST_MS," +
                        "RATE_HIT,CIRCUIT_HIT,CALL_AT,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) " +
                        "VALUES (?,?, '/api/open/test', ?, ?, ?, 0, 0, NOW(), 'smoke', NOW(), '0', 0)",
                "slac-" + reqId.toLowerCase(), caller, reqId, resp, costMs);
    }

    private void insertMetric(String partner, String key, String value, String level, String month) {
        jdbc.update("INSERT INTO erp_intf_sla_metric (ID,METRIC_KEY,PARTNER_CODE,API_DOMAIN," +
                        "WINDOW_START,WINDOW_END,METRIC_VALUE,UNIT,TARGET_VALUE,LEVEL,DATA_STATUS," +
                        "SAMPLE_COUNT,MONTH_TAG,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) " +
                        "VALUES (?,?,?,?,NOW(),NOW(),?,'%',? ,?,'OK',10,?,'smoke',NOW(),'0',0)",
                "slac-m-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                key, partner, "open-api", new BigDecimal(value), new BigDecimal(value), level, month);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_intf_sla_report WHERE MONTH_TAG=?", MONTH);
        jdbc.update("DELETE FROM erp_intf_sla_metric WHERE MONTH_TAG=? OR PARTNER_CODE LIKE 'slac%'",
                MONTH);
        jdbc.update("DELETE FROM erp_intf_sla_metric WHERE PARTNER_CODE LIKE 'slac%'");
        jdbc.update("DELETE FROM erp_intf_call_log WHERE REQ_ID LIKE 'SLACT-%'");
        jdbc.update("DELETE FROM erp_intf_sla_alert WHERE ALERT_KEY LIKE 'SLA_PUBLISH_BLOCK:%' " +
                "AND ALERT_WINDOW >= DATE_SUB(NOW(), INTERVAL 5 MINUTE)");
    }

    private JsonNode read(String json) {
        try {
            return om.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "slac-tester", "x", roles));
    }
}
