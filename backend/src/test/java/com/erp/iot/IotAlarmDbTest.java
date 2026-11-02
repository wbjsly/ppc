package com.erp.iot;

import com.erp.service.iot.IotAlarmService;
import com.erp.service.iot.IotDeviceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.6.3 阈值告警库测（add-iot-mobile，FR-4.11-5-3 / BR-4.11-33~36）：
 * 触发与 5min 合并 / 确认-处理-关闭闭环 / 超时升级 / 级别联动（L1 停机打点）。
 */
@SpringBootTest
class IotAlarmDbTest {

    @Autowired
    private IotAlarmService service;
    @Autowired
    private IotDeviceService deviceService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-alr-";
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
        jdbc.update("DELETE FROM erp_iot_alarm_event WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_device WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE REASON LIKE '%IOT-ALARM%'");
    }

    @Test
    void thresholdFiresAndMergesWithinWindow() {
        // severe=85 → 90 触发 L1
        Map<String, Object> r1 = service.evaluate(deviceNo, "temperature", new BigDecimal("90"));
        assertEquals(Boolean.TRUE, r1.get("alarm"));
        assertEquals(Boolean.FALSE, r1.get("merged"), "首次新建告警");
        // 同条件 5 分钟窗口内再触发 → 合并计数
        Map<String, Object> r2 = service.evaluate(deviceNo, "temperature", new BigDecimal("92"));
        assertEquals(Boolean.TRUE, r2.get("merged"), "窗口内合并");
        assertEquals(Boolean.TRUE, r1.get("eventNo").equals(r2.get("eventNo")), "同一事件号");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_alarm_event WHERE DEVICE_NO=? AND ALERT_TYPE='THRESHOLD'",
                Integer.class, deviceNo);
        assertEquals(1, cnt, "窗口内仅一条事件");
        // 未触发阈值
        Map<String, Object> r3 = service.evaluate(deviceNo, "temperature", new BigDecimal("50"));
        assertEquals(Boolean.FALSE, r3.get("alarm"));
    }

    @Test
    void confirmProcessCloseLifecycle() {
        Map<String, Object> r = service.evaluate(deviceNo, "temperature", new BigDecimal("90"));
        String eventNo = String.valueOf(r.get("eventNo"));
        // 确认 → 处理 → 关闭
        Map<String, Object> c = service.confirm(eventNo, "已确认");
        assertEquals("CONFIRMED", c.get("status"));
        Map<String, Object> p = service.processing(eventNo, "处理中");
        assertEquals("PROCESSING", p.get("status"));
        Map<String, Object> cl = service.close(eventNo, "已恢复");
        assertEquals("CLOSED", cl.get("status"));
        assertNotNull(cl.get("closeAt"));
        // 重复确认 409
        assertThrows(com.erp.common.ServiceException.class, () -> service.confirm(eventNo, "x"));
        // 重复关闭 409
        assertThrows(com.erp.common.ServiceException.class, () -> service.close(eventNo, "x"));
    }

    @Test
    void levelLinkageStopCommandRecorded() {
        // L1 联动：停机指令打点（网关仿真通道）
        Map<String, Object> r = service.evaluate(deviceNo, "temperature", new BigDecimal("95"));
        assertEquals(Boolean.TRUE, r.get("alarm"));
        @SuppressWarnings("unchecked")
        Map<String, Object> linkage = (Map<String, Object>) r.get("linkage");
        assertEquals("STOP_CMD", linkage.get("action"), "L1 停机联动");
        Map<String, Object> ev = jdbc.queryForMap(
                "SELECT STOP_CMD_AT, STOP_ACK_MS, ALERT_LEVEL FROM erp_iot_alarm_event WHERE EVENT_NO=?",
                r.get("eventNo"));
        assertEquals("L1", ev.get("ALERT_LEVEL"));
        assertNotNull(ev.get("STOP_CMD_AT"), "停机指令下发打点（BR-4.11-21）");
        assertNotNull(ev.get("STOP_ACK_MS"), "下发到确认时效记录");
    }

    @Test
    void overdueUnconfirmedEscalates() {
        Map<String, Object> r = service.evaluate(deviceNo, "temperature", new BigDecimal("90"));
        String eventNo = String.valueOf(r.get("eventNo"));
        // 拨回 31 分钟前（> 5min×6=30min）
        jdbc.update("UPDATE erp_iot_alarm_event SET OCCURRED_AT=DATE_SUB(NOW(), INTERVAL 31 MINUTE) " +
                "WHERE EVENT_NO=?", eventNo);
        Map<String, Object> out = service.scanEscalate();
        assertTrue(((Number) out.get("escalated")).intValue() >= 1, "超时未确认升级");
        String escalateTo = jdbc.queryForObject(
                "SELECT ESCALATE_TO FROM erp_iot_alarm_event WHERE EVENT_NO=?",
                String.class, eventNo);
        assertEquals("ROLE_EQUIP_MGR", escalateTo, "升级至设备主管");
    }
}
