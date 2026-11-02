package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.iot.gateway.GatewayRegistry;
import com.erp.iot.gateway.ModbusAdapter;
import com.erp.iot.gateway.MqttAdapter;
import com.erp.iot.gateway.OpcUaAdapter;
import com.erp.iot.gateway.SimulatedDriver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网关适配层单测（add-iot-mobile 任务 6.1）：
 * 三协议编解码（MQTT/OPC-UA/Modbus）/ 证书握手失败 L1 拒绝 / 断线重连退避。
 */
class GatewayAdapterTest {

    private final SimulatedDriver driver = new SimulatedDriver();
    private final MqttAdapter mqtt = new MqttAdapter(driver);
    private final OpcUaAdapter opc = new OpcUaAdapter(driver);
    private final ModbusAdapter tcp = new ModbusAdapter(driver, ModbusAdapter.TCP);
    private final ModbusAdapter rtu = new ModbusAdapter(driver, ModbusAdapter.RTU);

    @Test
    void mqttFrameEncodeDecode() {
        byte[] conn = mqtt.encodeConnect("CERT-ABC123");
        assertEquals(0x10, conn[0] & 0xFF, "CONNECT 固定头");
        assertEquals(4, conn[8], "协议级别 4（3.1.1）");
        // 回环 → CONNACK 解码
        SimulatedDriver.Result r = driver.roundTrip("CERT-ABC123", conn);
        assertTrue(r.ok(), "回环链路通（证书校验通过）");
        // 标准 CONNACK 帧解码（真实 broker 布局）
        assertEquals(0, mqtt.decodeConnack(new byte[]{0x20, 0x02, 0x00, 0x00}), "rc=0 接受");
        assertEquals(5, mqtt.decodeConnack(new byte[]{0x20, 0x02, 0x00, 0x05}), "rc=5 拒绝");
        assertEquals(-1, mqtt.decodeConnack(new byte[]{0x01, 0x02}), "非法帧 -1");
        // PUBLISH 帧
        byte[] pub = mqtt.encodePublish("iot/dev/cmd", "STOP");
        assertEquals(0x30, pub[0] & 0xFF, "PUBLISH QoS0 固定头");
        assertEquals("iot/dev/cmd".length(), (pub[2] << 8) | pub[3], "topic 长度");
    }

    @Test
    void opcUaHelloAckRoundTrip() {
        byte[] hello = opc.encodeHello("opc.tcp://dev1:4840");
        assertEquals('H', hello[0]);
        assertEquals('E', hello[1]);
        assertEquals('L', hello[2]);
        assertEquals('F', hello[3]);
        SimulatedDriver.Result r = driver.roundTrip("CERT-OPC1", hello);
        assertTrue(r.ok(), "回环链路通（证书校验通过）");
        // 标准 ACKF 帧解码（真实端点布局）：'ACKF' + 声明长度 LE
        byte[] ack = new byte[]{'A', 'C', 'K', 'F', 8, 0, 0, 0};
        assertTrue(opc.decodeAck(ack), "标准 ACKF 解码通过");
        // 非法帧拒绝
        assertFalse(opc.decodeAck(new byte[]{0x00, 0x01, 0x02}), "非 ACKF 帧拒绝");
        assertFalse(opc.decodeAck(new byte[]{'A', 'C', 'K', 'F', 99, 0, 0, 0}), "长度不符拒绝");
    }

    @Test
    void modbusTcpAndRtuFrames() {
        // TCP ADU
        byte[] f = tcp.encodeReadHolding(0x0001, 1, 0, 1);
        assertEquals(0x00, f[0]);
        assertEquals(0x01, f[1], "事务 ID");
        assertEquals(0x00, f[2]);
        assertEquals(0x00, f[3], "协议 ID=0");
        assertEquals(6, f[5], "长度=单元1+功能1+数据4");
        assertEquals(0x03, f[7], "功能码 03 读保持寄存器");
        // RTU 帧 + CRC16
        byte[] rtuFrame = rtu.encodeReadHolding(0, 1, 0, 1);
        assertEquals(8, rtuFrame.length, "RTU 帧长 8");
        int crc = ModbusAdapter.crc16(rtuFrame, 6);
        assertEquals(crc & 0xFF, rtuFrame[6] & 0xFF, "CRC 低字节");
        assertEquals((crc >> 8) & 0xFF, rtuFrame[7] & 0xFF, "CRC 高字节");
        // 异常响应解码（功能码|0x80）
        byte[] bad = new byte[9];
        bad[7] = (byte) 0x83;
        bad[8] = 0x02;
        ServiceException ex = assertThrows(ServiceException.class,
                () -> tcp.decodeResponse(bad));
        assertTrue(ex.getMessage().contains("异常响应"));
    }

    @Test
    void invalidCertHandshakeRejectedL1() {
        // 证书无效（非 CERT- 前缀 / REVOKED）→ 各协议握手均 L1 409
        for (String bad : new String[]{"BAD-CERT", "CERT-X-REVOKED-Y"}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> mqtt.handshake(bad, "dev1"));
            assertEquals(409, ex.getCode());
        }
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> opc.handshake("PLAINTEXT", "dev2"));
        assertEquals(409, ex1.getCode());
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> tcp.handshake("NOPE", "dev3"));
        assertEquals(409, ex2.getCode());
    }

    @Test
    void handshakeSucceedsWithValidCert() {
        Map<String, Object> r = mqtt.handshake("CERT-VALID1", "dev1");
        assertEquals(Boolean.TRUE, r.get("handshake"));
        assertNotNull(r.get("sessionKey"));
        Map<String, Object> o = opc.handshake("CERT-VALID2", "dev2");
        assertEquals(Boolean.TRUE, o.get("handshake"));
        Map<String, Object> m = tcp.handshake("CERT-VALID3", "dev3");
        assertEquals(Boolean.TRUE, m.get("handshake"));
    }

    @Test
    void reconnectBacksOffUntilSuccess() {
        // 注入 2 次故障 → 第 3 次成功（退避路径走通）
        driver.injectFailures("CERT-SIM-dev-recon", 2);
        long t0 = System.currentTimeMillis();
        mqtt.reconnect("dev-recon");
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(3, driver.attemptsOf("dev-recon"), "3 次尝试后成功");
        assertTrue(elapsed >= 3000, "指数退避累计 ≥3s（1s+2s），实际 " + elapsed + "ms");
    }

    @Test
    void reconnectFailsAfterMaxAttempts() {
        // 注入 99 次故障 → 5 次退避后上抛
        driver.injectFailures("CERT-SIM-dev-recon2", 99);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> opc.reconnect("dev-recon2"));
        assertTrue(ex.getMessage().contains("重连失败"));
        assertEquals(5, driver.attemptsOf("dev-recon2"), "最多 5 次尝试");
    }

    @Test
    void registryRoutesByProtocolWithPriority() {
        GatewayRegistry registry = new GatewayRegistry(driver);
        assertEquals("OPC-UA", registry.byProtocol("OPC-UA").protocol());
        assertEquals("MQTT", registry.byProtocol("MQTT").protocol());
        assertEquals(ModbusAdapter.TCP, registry.byProtocol("MODBUS_TCP").protocol());
        assertEquals(ModbusAdapter.RTU, registry.byProtocol("MODBUS_RTU").protocol());
        // 协议优先级：OPC-UA=1 > MQTT=2 > TCP=3 > RTU=4（FR-4.11-5-1 要点②）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> registry.byProtocol("COAP"));
        assertEquals(422, ex.getCode());
    }
}
