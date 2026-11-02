package com.erp.iot;

import com.erp.schedule.IotTelemetrySimulator;
import com.erp.service.iot.IotDeviceService;
import com.erp.service.iot.IotTelemetryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.6.2/13.6.3 测点时序与仿真库测（add-iot-mobile，FR-4.11-5-2）：
 * 注入写入 / 可疑标记过滤 / 仿真器生成 / 分区存在 / 归档维护。
 */
@SpringBootTest
class IotTelemetryDbTest {

    @Autowired
    private IotTelemetryService service;
    @Autowired
    private IotDeviceService deviceService;
    @Autowired
    private IotTelemetrySimulator simulator;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-tel-";
    private String deviceNo;

    @BeforeEach
    void setUp() {
        purge();
        deviceNo = P + UUID.randomUUID().toString().substring(0, 8);
        deviceService.register(Map.of("deviceNo", deviceNo, "deviceType", "SENSOR",
                "protocol", "MQTT", "certSn", "CERT-" + deviceNo,
                "alertRuleJson", "{\"temperature\":{\"pre\":60,\"warn\":70,\"severe\":85}}"));
        deviceService.connectTest(deviceNo);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_telemetry WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_device WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_alarm_event WHERE DEVICE_NO LIKE ?", P + "%");
    }

    @Test
    void injectWritesAndFiltersSuspect() {
        // 注入 GOOD 50℃ + SUSPECT -999
        int n = service.inject(Map.of("points", List.of(
                Map.of("deviceNo", deviceNo, "pointCode", "temperature",
                        "val", new BigDecimal("50"), "quality", "GOOD"),
                Map.of("deviceNo", deviceNo, "pointCode", "temperature",
                        "val", new BigDecimal("-999"), "quality", "SUSPECT"))));
        assertEquals(2, n, "两点注入成功");
        // SUSPECT 进可疑清单
        List<Map<String, Object>> suspects = service.suspectList(deviceNo, 10);
        assertEquals(1, suspects.size(), "可疑数据入清单");
        // GOOD 参与时序查询，SUSPECT 也在查询结果中（quality 标记消费侧过滤）
        List<Map<String, Object>> rows = service.query(deviceNo, "temperature",
                LocalDateTime.now().minusHours(1).toString(),
                LocalDateTime.now().plusHours(1).toString(), 100);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "GOOD".equals(r.get("quality"))));
        assertTrue(rows.stream().anyMatch(r -> "SUSPECT".equals(r.get("quality"))));
        // 最新测点
        Map<String, Object> latest = service.latest(deviceNo, "temperature");
        assertEquals(deviceNo, latest.get("deviceNo"));
    }

    @Test
    void suspectNotTriggerAlarmButGoodDoes() {
        // SUSPECT 高值 → 不触发告警；GOOD 高值（severe=85）→ 触发 L1 + 停机联动
        service.inject(Map.of("points", List.of(
                Map.of("deviceNo", deviceNo, "pointCode", "temperature",
                        "val", new BigDecimal("999"), "quality", "SUSPECT"))));
        Integer alarmsAfterSuspect = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_alarm_event WHERE DEVICE_NO=?", Integer.class, deviceNo);
        assertEquals(0, alarmsAfterSuspect, "SUSPECT 被告警过滤（FR-4.11-5-2 异常处理②）");
        service.inject(Map.of("points", List.of(
                Map.of("deviceNo", deviceNo, "pointCode", "temperature",
                        "val", new BigDecimal("90"), "quality", "GOOD"))));
        Integer alarmsAfterGood = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_alarm_event WHERE DEVICE_NO=? AND ALERT_LEVEL='L1'",
                Integer.class, deviceNo);
        assertEquals(1, alarmsAfterGood, "GOOD 高值触发 L1 严重告警");
    }

    @Test
    void simulatorGeneratesPointsForOnlineDevice() {
        // 在线设备（setUp 已 connectTest）→ runOnce 采样落库
        int r = simulator.runOnce();
        assertEquals(1, r);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_telemetry WHERE DEVICE_NO=?", Integer.class, deviceNo);
        assertTrue(cnt >= 2, "仿真采样至少温度+振动两点，实际 " + cnt);
    }

    @Test
    void partitionedByMonthExists() {
        // 时序表按月 RANGE 分区（迁移 208：p2026_11 ~ p2027_12 + pmax）
        Integer parts = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT PARTITION_NAME) FROM information_schema.PARTITIONS " +
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='erp_iot_telemetry'",
                Integer.class);
        assertTrue(parts >= 15, "分区数 ≥15（14 月 + pmax），实际 " + parts);
    }

    @Test
    void maintainPrunesOldRows() {
        // 直插一条 100 天前的旧行 → maintain 清理
        jdbc.update("INSERT INTO erp_iot_telemetry (ID, TS, DEVICE_NO, POINT_CODE, VAL, QUALITY, CREATE_DATE) " +
                        "VALUES (?, DATE_SUB(NOW(), INTERVAL 100 DAY), ?, 'temperature', 1, 'GOOD', NOW())",
                UUID.randomUUID().toString().replace("-", ""), deviceNo);
        Map<String, Object> out = service.maintain();
        assertTrue(((Number) out.get("prunedRows")).intValue() >= 1, "超 90 天原始行清理");
        Integer old = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_telemetry WHERE DEVICE_NO=? AND TS < DATE_SUB(NOW(), INTERVAL 90 DAY)",
                Integer.class, deviceNo);
        assertEquals(0, old);
    }
}
