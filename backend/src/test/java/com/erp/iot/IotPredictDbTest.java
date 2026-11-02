package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotAlarmService;
import com.erp.service.iot.IotDeviceService;
import com.erp.service.iot.IotPredictService;
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
 * 13.8 预测维护库测（add-iot-mobile，FR-4.11-8-x / BR-4.11-48~53）：
 * 幂等建单（重复 409 语义）/ 误报率统计（>20% 提示复核）/ 工单完成闭环回写。
 */
@SpringBootTest
class IotPredictDbTest {

    @Autowired
    private IotPredictService service;
    @Autowired
    private IotAlarmService alarmService;
    @Autowired
    private IotDeviceService deviceService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-prd-";
    private String deviceNo;

    @BeforeEach
    void setUp() {
        purge();
        deviceNo = P + UUID.randomUUID().toString().substring(0, 8);
        deviceService.register(Map.of("deviceNo", deviceNo, "protocol", "MQTT",
                "certSn", "CERT-" + deviceNo));
        deviceService.connectTest(deviceNo);
        // 建一张趋势告警（TREND 类，不走 evaluate——直插保证形态）
        jdbc.update("INSERT INTO erp_iot_alarm_event (ID, EVENT_NO, DEVICE_NO, ALERT_LEVEL, " +
                        "ALERT_TYPE, RULE_SNAP, MERGE_KEY, MERGE_CNT, STATUS, OCCURRED_AT, " +
                        "CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?, 'L2', 'TREND', '7日斜率 3.2/日 ≥ 3', 'TREND:x', 1, 'OPEN', " +
                        "NOW(), NOW(), '0', 0)",
                P + "ev-id", P + "ALM-1", deviceNo);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_alarm_event WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_eam_work_order WHERE IDEMPOTENT_KEY LIKE ?", "IOT-ALARM:" + P + "%");
        jdbc.update("DELETE FROM erp_iot_device WHERE DEVICE_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_mobile_todo WHERE TITLE LIKE '%扫码%' ");
    }

    @Test
    void createWoIsIdempotentByEventId() {
        // 首次建单
        Map<String, Object> r1 = service.createWoIdempotent(P + "ALM-1");
        assertEquals(Boolean.FALSE, r1.get("duplicate"));
        assertNotNull(r1.get("woNo"));
        // 重复触发 → 幂等返回既有单（duplicate=true，Controller 映射 409）
        Map<String, Object> r2 = service.createWoIdempotent(P + "ALM-1");
        assertEquals(Boolean.TRUE, r2.get("duplicate"), "幂等键=告警事件 ID");
        assertEquals(r1.get("woNo"), r2.get("woNo"));
        // 队列仅一张工单
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_work_order WHERE IDEMPOTENT_KEY=?",
                Integer.class, "IOT-ALARM:" + P + "ev-id");
        assertEquals(1, cnt, "重复触发不重复建单（C-4.11-10）");
        // LINK_WO_ID 回写
        String link = jdbc.queryForObject(
                "SELECT LINK_WO_ID FROM erp_iot_alarm_event WHERE EVENT_NO=?",
                String.class, P + "ALM-1");
        assertEquals(r1.get("woNo"), link, "告警事件回写建单指向");
        // 待评估列表不再含该事件
        assertTrue(service.trendCandidates().stream()
                .noneMatch(c -> (P + "ALM-1").equals(c.get("eventNo"))));
    }

    @Test
    void falsePositiveRateOverTwentyPercentHints() {
        // 造 5 张 TREND 告警：3 张误报 → 60% > 20%
        for (int i = 2; i <= 6; i++) {
            jdbc.update("INSERT INTO erp_iot_alarm_event (ID, EVENT_NO, DEVICE_NO, ALERT_LEVEL, " +
                            "ALERT_TYPE, RULE_SNAP, MERGE_KEY, MERGE_CNT, STATUS, FALSE_POSITIVE, " +
                            "OCCURRED_AT, CREATE_DATE, DEL_FLAG, VER_NO) " +
                            "VALUES (?,?,?, 'L2', 'TREND', 'x', ?, 1, 'CLOSED', ?, NOW(), NOW(), '0', 0)",
                    P + "ev-" + i, P + "ALM-" + i, deviceNo, "k" + i,
                    i <= 4 ? "1" : "0");   // 2,3,4 误报
        }
        Map<String, Object> rate = service.falsePositiveRate();
        long total = ((Number) rate.get("totalWo")).longValue();
        long fp = ((Number) rate.get("falsePositive")).longValue();
        assertTrue(total >= 5, "30 天窗口 TREND 样本，实际 " + total);
        assertEquals(3, fp, "误报计数");
        assertTrue(((Number) rate.get("ratePct")).doubleValue() > 20, "误报率 > 20%");
        assertEquals(Boolean.TRUE, rate.get("overThreshold"));
        assertTrue(String.valueOf(rate.get("hint")).contains("复核"), "超阈值提示复核阈值");
    }

    @Test
    void woClosedWritesBackAlarmClosure() {
        service.createWoIdempotent(P + "ALM-1");
        String woNo = jdbc.queryForObject(
                "SELECT LINK_WO_ID FROM erp_iot_alarm_event WHERE EVENT_NO=?",
                String.class, P + "ALM-1");
        // 工单完成 → 告警闭环回写
        Map<String, Object> out = service.onWoClosed(woNo);
        assertEquals(1, out.get("closedAlarms"), "工单关闭回写告警 CLOSED");
        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_iot_alarm_event WHERE EVENT_NO=?",
                String.class, P + "ALM-1");
        assertEquals("CLOSED", status);
        // 误报标记
        Map<String, Object> fp = service.markFalsePositive(P + "ALM-1", true);
        assertEquals(Boolean.TRUE, fp.get("falsePositive"));
        String fpFlag = jdbc.queryForObject(
                "SELECT FALSE_POSITIVE FROM erp_iot_alarm_event WHERE EVENT_NO=?",
                String.class, P + "ALM-1");
        assertEquals("1", fpFlag, "误报标记落库（BR-4.11-52 统计口径）");
    }
}
