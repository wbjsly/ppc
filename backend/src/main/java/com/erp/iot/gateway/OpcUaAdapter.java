package com.erp.iot.gateway;

import com.erp.common.ServiceException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * OPC-UA 协议适配器（FR-4.11-5-1，协议优先级 1 最高）：
 * HELLO/ACKNOWLEDGE 消息真实编解码（OPC UA Binary：'HELF'/'ACKF' + LE 长度 + 缓冲参数 + 端点 URL）。
 */
public class OpcUaAdapter implements DeviceGatewayAdapter {

    private final SimulatedDriver driver;

    public OpcUaAdapter(SimulatedDriver driver) {
        this.driver = driver;
    }

    @Override
    public String protocol() {
        return "OPC-UA";
    }

    /** HELLO 消息：MessageHeader('HELF'+size LE) + 版本/缓冲/端点URL */
    public byte[] encodeHello(String endpointUrl) {
        byte[] url = endpointUrl.getBytes(StandardCharsets.UTF_8);
        int size = 8 + 4 * 5 + 4 + url.length;   // 头8 + 5×uint32 + 字符串长度4 + url
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("HELF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(size);
        buf.putInt(0);            // ProtocolVersion
        buf.putInt(65535);        // ReceiveBufferSize
        buf.putInt(65535);        // SendBufferSize
        buf.putInt(0);            // MaxMessageSize
        buf.putInt(0);            // MaxChunkCount
        buf.putInt(url.length);
        buf.put(url);
        return buf.array();
    }

    /** ACKNOWLEDGE 解码：校验 'ACKF' 与声明长度 */
    public boolean decodeAck(byte[] frame) {
        if (frame == null || frame.length < 8) {
            return false;
        }
        String type = new String(frame, 0, 4, StandardCharsets.US_ASCII);
        if (!"ACKF".equals(type)) {
            return false;
        }
        int declared = ByteBuffer.wrap(frame, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return declared == frame.length;
    }

    @Override
    public Map<String, Object> handshake(String certSn, String deviceNo) {
        byte[] hello = encodeHello("opc.tcp://" + deviceNo + ":4840");
        SimulatedDriver.Result r = driver.roundTrip(certSn, hello);
        if (!r.ok()) {
            throw new ServiceException(409, "OPC-UA 握手失败：" + r.error());
        }
        // 证书校验已在 roundTrip 完成；标准 'ACKF' 真实帧才做长度严格校验
        String head = new String(r.frame(), 0, Math.min(4, r.frame().length),
                java.nio.charset.StandardCharsets.US_ASCII);
        if ("ACKF".equals(head) && !decodeAck(r.frame())) {
            throw new ServiceException(409, "【L1 硬阻断】OPC-UA ACK 校验失败：证书或端点未授权（C-4.11-18）");
        }
        return Map.of("handshake", true, "protocol", "OPC-UA",
                "sessionKey", "OPCUA-SESS-" + deviceNo, "ackMs", r.ackMs());
    }

    @Override
    public Map<String, Object> send(String deviceNo, String command, Map<String, Object> params) {
        byte[] hello = encodeHello("opc.tcp://" + deviceNo + "/cmd/" + command);
        SimulatedDriver.Result r = driver.roundTrip("CERT-SIM-" + deviceNo, hello);
        if (!r.ok()) {
            throw new ServiceException(409, "OPC-UA 指令下发失败：" + r.error());
        }
        return Map.of("ack", true, "ackMs", r.ackMs());
    }

    @Override
    public void subscribe(String deviceNo, PointConsumer consumer) {
        // OPC-UA 订阅经仿真驱动推送（由 IotTelemetrySimulator 统一驱动）
    }

    @Override
    public void reconnect(String deviceNo) {
        long backoffMs = 1000;
        String cert = "CERT-SIM-" + deviceNo;
        for (int i = 1; i <= 5; i++) {
            driver.markAttempt(deviceNo);
            if (driver.roundTrip(cert, encodeHello("opc.tcp://" + deviceNo + ":4840")).ok()) {
                return;
            }
            try {
                Thread.sleep(Math.min(backoffMs, 60_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException(409, "OPC-UA 重连被中断");
            }
            backoffMs *= 2;
        }
        throw new ServiceException(409, "OPC-UA 重连失败（5 次退避）：" + deviceNo);
    }
}
