package com.erp.ops;

import com.erp.common.ServiceException;
import com.erp.service.ops.OpsSlaReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 18.5 运维 SLA 报告库测（add-ops-sla，BR-5.8-07/16）：
 * 五指标计算 / 未达标改进项 / 未归档发布 422 / 归档发布链。
 */
@SpringBootTest
class OpsSlaReportDbTest {

    @Autowired
    private OpsSlaReportService service;
    @Autowired
    private JdbcTemplate jdbc;

    /** 用未来月份避免撞调度器自动报告 */
    private static final String P = "2099-06";

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void generateProducesFiveItems() {
        Map<String, Object> snap = service.generate(P);
        assertEquals(P, snap.get("period"));
        assertEquals("DRAFT", snap.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) snap.get("metrics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) metrics.get("items");
        assertEquals(5, items.size(), "五指标全产出");
    }

    @Test
    void metTargetsNoImprovement() {
        // 无告警/变更样本 → 各率视为 100% 达标（容量平均 0 反向达标）→ 无改进项
        Map<String, Object> snap = service.generate(P);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) snap.get("metrics");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) metrics.get("improvementIds");
        assertTrue(ids == null || ids.isEmpty(), "全达标无改进项");
    }

    @Test
    void unmetTargetCreatesImprovement() {
        // 造未达标样本：告警全部未响应（OPEN）→ 告警响应率 0% < 95%
        jdbc.update("INSERT INTO erp_ops_alert (ID, ALERT_NO, LEVEL, SOURCE, TITLE, STATUS, "
                        + "SUPPRESSED, ESCALATION_LEVEL, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('imp-a1', 'IMP-A1', 'P2', 'manual', '未响应告警', 'OPEN', '0', 0, "
                        + "'t', '2099-06-10 10:00:00', '0', 0)");
        Map<String, Object> snap = service.generate(P);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) snap.get("metrics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) metrics.get("items");
        boolean alertMet = items.stream()
                .filter(i -> "alertResponseRate".equals(i.get("key")))
                .allMatch(i -> Boolean.TRUE.equals(i.get("met")));
        assertFalse(alertMet, "告警响应率未达标");
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) metrics.get("improvementIds");
        assertTrue(ids != null && !ids.isEmpty(), "自动生成改进项（BR-5.8-07）");
        // 改进项台账可查
        List<Map<String, Object>> imps = service.improvements(null, 10);
        assertFalse(imps.isEmpty());
    }

    @Test
    void publishBlockedWhenNotArchived() {
        service.generate(P);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.publish(P, "ALL"));
        assertEquals(422, ex.getCode(), "未归档发布阻断（BR-5.8-16）");
        // 阻断留痕
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='OpsSlaPublishBlocked' AND BIZ_ID=?",
                Integer.class, P);
        assertTrue(n >= 1, "发布尝试留痕");
    }

    @Test
    void archiveThenPublish() {
        service.generate(P);
        Map<String, Object> a = service.archive(P);
        assertEquals("ARCHIVED", a.get("status"));
        Map<String, Object> p = service.publish(P, "PARTNER");
        assertEquals("PUBLISHED", p.get("status"));
        assertEquals("PARTNER", p.get("publishScope"));
    }

    @Test
    void reportSnapshotPersists() {
        service.generate(P);
        Map<String, Object> r = service.report(P);
        assertNotNull(r.get("metrics"));
        // 幂等重生成覆盖
        service.generate(P);
        assertEquals("DRAFT", service.report(P).get("status"));
    }

    @Test
    void invalidPeriodRejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.generate("bad"));
        assertEquals(422, ex.getCode());
    }

    @Test
    void reportMissing404() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.report("2099-12"));
        assertEquals(404, ex.getCode());
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_ops_incident WHERE INC_NO LIKE 'IMP-209906%'");
        jdbc.update("DELETE FROM erp_ops_alert WHERE ID='imp-a1'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='OpsSlaPublishBlocked' AND BIZ_ID=?", P);
        jdbc.update("DELETE FROM erp_ops_sla_report WHERE PERIOD=?", P);
    }
}
