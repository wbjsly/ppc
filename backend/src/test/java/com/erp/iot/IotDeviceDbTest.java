package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.schedule.IotHeartbeatScheduler;
import com.erp.service.iot.IotDeviceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.6.1 设备接入库测（add-iot-mobile，FR-4.11-5-1 / C-4.11-18）：
 * 注册连接测试通过 / 证书无效 L1 拒绝 / 心跳超时置离线。
 */
@SpringBootTest
class IotDeviceDbTest {

    @Autowired
    private IotDeviceService service;
    @Autowired
    private IotHeartbeatScheduler heartbeatScheduler;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-dev-";

    @BeforeEach
    void setUp() {
        purge();
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

    private String devNo() {
        return P + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void registerThenConnectTestPasses() {
        String no = devNo();
        Map<String, Object> reg = service.register(Map.of(
                "deviceNo", no, "deviceType", "SENSOR", "protocol", "MQTT",
                "sampleFreqSec", 1, "certSn", "CERT-" + no,
                "alertRuleJson", "{\"temperature\":{\"pre\":60,\"warn\":70,\"severe\":85}}"));
        assertEquals("OFFLINE", reg.get("status"), "注册初始离线");
        assertEquals(2, reg.get("protocolPriority"), "MQTT 优先级 2");
        // 连接测试（网关握手）→ 在线
        Map<String, Object> test = service.connectTest(no);
        assertEquals("ONLINE", test.get("status"), "连接测试通过置在线");
        assertNotNull(test.get("handshake"));
        assertNotNull(test.get("heartbeatAt"));
    }

    @Test
    void revokedCertRejectedL1() {
        String no = devNo();
        service.register(Map.of("deviceNo", no, "protocol", "MQTT",
                "certSn", "CERT-" + no + "-REVOKED"));
        // 吊销证书
        jdbc.update("UPDATE erp_iot_device SET CERT_STATUS='REVOKED' WHERE DEVICE_NO=?", no);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.connectTest(no));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("L1"), "证书无效 L1 拒绝接入（C-4.11-18）");
        // 心跳同样拒绝
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> service.heartbeat(no));
        assertEquals(409, ex2.getCode());
    }

    @Test
    void heartbeatTimeoutMarksOffline() {
        String no = devNo();
        service.register(Map.of("deviceNo", no, "protocol", "MQTT", "certSn", "CERT-" + no));
        service.connectTest(no);
        // 拨回心跳 6 分钟前（> IOT_HEARTBEAT_TIMEOUT_MIN=5）
        jdbc.update("UPDATE erp_iot_device SET HEARTBEAT_AT=DATE_SUB(NOW(), INTERVAL 6 MINUTE) " +
                "WHERE DEVICE_NO=?", no);
        heartbeatScheduler.sweep();
        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_iot_device WHERE DEVICE_NO=?", String.class, no);
        assertEquals("OFFLINE", status, "心跳丢失 >5min 置离线（FR-4.11-5-2 异常处理①）");
        // 心跳恢复 → 在线
        service.heartbeat(no);
        assertEquals("ONLINE", service.detail(no).get("status"));
    }

    @Test
    void statusDistributionCounts() {
        String no = devNo();
        service.register(Map.of("deviceNo", no, "protocol", "MQTT", "certSn", "CERT-" + no));
        service.connectTest(no);
        Map<String, Object> dist = service.statusDistribution();
        assertTrue(((Number) dist.get("ONLINE")).longValue() >= 1, "在线计数含本设备");
    }
}
