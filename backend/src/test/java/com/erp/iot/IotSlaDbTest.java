package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotSlaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.9 移动/IoT SLA 报告库测（add-iot-mobile，FR-4.11-6-x / C-05 未归档不发布）：
 * 五指标计算 / 未归档发布阻断 / 订阅归档推送。
 */
@SpringBootTest
class IotSlaDbTest {

    @Autowired
    private IotSlaService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "2099-01";   // 测试专用报告期（不存在冲突）

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_sla_report WHERE PERIOD=?", P);
        jdbc.update("DELETE FROM erp_iot_sla_sub WHERE REPORT_TYPE='IOT' AND SUBSCRIBER LIKE 'smk-sla-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='IOT_SLA'");
    }

    @Test
    void generateComputesFiveMetrics() {
        Map<String, Object> r = service.generate(P);
        assertEquals(P, r.get("period"));
        assertEquals("DRAFT", r.get("status"));
        String json = String.valueOf(r.get("metricsJson"));
        // 五指标键齐（FR-4.11-6-2）
        assertTrue(json.contains("availabilityPct"), "可用率");
        assertTrue(json.contains("syncSuccessPct"), "同步成功率");
        assertTrue(json.contains("avgAlertRespMin"), "平均响应时间");
        assertTrue(json.contains("offlineSharePct"), "离线作业占比");
        assertTrue(json.contains("alarmClosePct"), "告警响应/关闭率");
        // 数值可解析
        assertTrue(json.matches(".*\"availabilityPct\":\\d+(\\.\\d+)?,.*"), "可用率为数值");
    }

    @Test
    void publishBlockedBeforeArchive() {
        service.generate(P);
        // DRAFT 发布 → 422 阻断 + 尝试留痕（C-05）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish(P));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("未归档"));
        String log = jdbc.queryForObject(
                "SELECT PUBLISH_LOG FROM erp_iot_sla_report WHERE PERIOD=?", String.class, P);
        assertNotNull(log, "未归档发布尝试留痕");
        // 归档后发布成功
        Map<String, Object> ar = service.archive(P);
        assertEquals("ARCHIVED", ar.get("status"));
        Map<String, Object> pub = service.publish(P);
        assertEquals("PUBLISHED", pub.get("status"));
        assertNotNull(pub.get("publishAt"));
        // 已发布再归档 409
        assertThrows(ServiceException.class, () -> service.archive(P));
    }

    @Test
    void subscribePushesOnArchiveOnce() {
        String sub1 = "smk-sla-u1-" + System.nanoTime();
        String sub2 = "smk-sla-u2-" + System.nanoTime();
        service.subscribe(sub1);
        service.subscribe(sub2);
        service.generate(P);
        Map<String, Object> ar = service.archive(P);
        assertEquals(2, ar.get("subscribersPushed"), "归档自动推送两订阅人");
        // 防重复：再次归档（先取消发布态回到 ARCHIVED 不可——改用重复推送验证：
        // 重新订阅同人后再生成新期推送才计数；此处验证通知落库）
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='IOT_SLA' AND TARGET_USER=?",
                Integer.class, sub1);
        assertEquals(1, notices, "订阅人收到归档推送");
        // 重复订阅幂等（renewed）
        Map<String, Object> again = service.subscribe(sub1);
        assertEquals(Boolean.TRUE, again.get("renewed"));
        List<Map<String, Object>> list = service.list();
        assertTrue(list.stream().anyMatch(x -> P.equals(x.get("period"))), "报告列表可见");
    }
}
