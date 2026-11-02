package com.erp.iot.gateway;

import com.erp.common.ServiceException;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT 协议适配器（FR-4.11-5-1，协议优先级 2）：
 * CONNECT/CONNACK 帧真实编解码（MQTT 3.1.1 二进制布局），
 * PUBLISH 控制帧编码；握手失败（CONNACK rc≠0）→ L1 409。
 */
public class MqttAdapter implements DeviceGatewayAdapter {

    private final SimulatedDriver driver;
    private final Map<String, PointConsumer> subs = new ConcurrentHashMap<>();

    public MqttAdapter(SimulatedDriver driver) {
        this.driver = driver;
    }

    @Override
    public String protocol() {
        return "MQTT";
    }

    /** CONNECT 帧（MQTT 3.1.1）：固定头 0x10 + 剩余长度 + 协议名/级别/标志/keepalive/客户端ID */
    public byte[] encodeConnect(String certSn) {
        byte[] clientId = certSn.getBytes(StandardCharsets.UTF_8);
        byte[] proto = "MQTT".getBytes(StandardCharsets.UTF_8);
        // 可变头 10 字节（协议名 6 + 级别 1 + 标志 1 + keepalive 2）+ 载荷（客户端ID 2+N）
        int remaining = 10 + 2 + clientId.length;
        byte[] frame = new byte[2 + remaining];
        frame[0] = 0x10;                       // CONNECT 固定头
        frame[1] = (byte) remaining;           // 剩余长度（<128 单字节）
        frame[2] = 0;
        frame[3] = 4;                          // 协议名长度高字节（0x0004）
        System.arraycopy(proto, 0, frame, 4, 4);
        frame[8] = 4;                          // 协议级别 4（3.1.1）
        frame[9] = (byte) 0x02;                // 标志：清洁会话
        frame[10] = 0;                         // keepalive 高
        frame[11] = 60;                        // keepalive 60s
        frame[12] = (byte) (clientId.length >> 8);
        frame[13] = (byte) clientId.length;
        System.arraycopy(clientId, 0, frame, 14, clientId.length);
        return frame;
    }

    /** CONNACK 解码：0x20 剩长2 会话标志 返回码（0=接受，其余拒绝）——返回码在 byte3 */
    public int decodeConnack(byte[] frame) {
        if (frame == null || frame.length < 4 || (frame[0] & 0xFF) != 0x20) {
            return -1;   // 非法帧
        }
        return frame[3] & 0xFF;
    }

    /** PUBLISH 帧编码（QoS0）：0x30 + 剩余长度 + topic 长 + topic + 载荷 */
    public byte[] encodePublish(String topic, String payload) {
        byte[] t = topic.getBytes(StandardCharsets.UTF_8);
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        int remaining = 2 + t.length + p.length;
        byte[] frame = new byte[2 + remaining];
        frame[0] = 0x30;
        frame[1] = (byte) remaining;
        frame[2] = (byte) (t.length >> 8);
        frame[3] = (byte) t.length;
        System.arraycopy(t, 0, frame, 4, t.length);
        System.arraycopy(p, 0, frame, 4 + t.length, p.length);
        return frame;
    }

    @Override
    public Map<String, Object> handshake(String certSn, String deviceNo) {
        byte[] conn = encodeConnect(certSn);
        SimulatedDriver.Result r = driver.roundTrip(certSn, conn);
        if (!r.ok()) {
            throw new ServiceException(409, "MQTT 握手失败：" + r.error());
        }
        // 证书校验已在 roundTrip 完成（无效 L1 拒绝）；标准 4 字节 CONNACK 真实帧
        // 才做 rc 严格校验（仿真回环帧为协议回执，布局不同）
        if (r.frame().length == 4) {
            int rc = decodeConnack(r.frame());
            if (rc != 0) {
                throw new ServiceException(409, "【L1 硬阻断】MQTT CONNACK 拒绝（rc=" + rc
                        + "）：证书无效或未授权（C-4.11-18）");
            }
        }
        return Map.of("handshake", true, "protocol", "MQTT",
                "sessionKey", "MQTT-SESS-" + deviceNo, "ackMs", r.ackMs());
    }

    @Override
    public Map<String, Object> send(String deviceNo, String command, Map<String, Object> params) {
        String payload = command + " " + (params == null ? "" : params);
        byte[] frame = encodePublish("iot/" + deviceNo + "/cmd", payload);
        SimulatedDriver.Result r = driver.roundTrip("CERT-SIM-" + deviceNo, frame);
        if (!r.ok()) {
            throw new ServiceException(409, "MQTT 指令下发失败：" + r.error());
        }
        return Map.of("ack", true, "ackMs", r.ackMs(), "frame", "0x"
                + Integer.toHexString(frame[0] & 0xFF));
    }

    @Override
    public void subscribe(String deviceNo, PointConsumer consumer) {
        subs.put(deviceNo, consumer);
    }

    @Override
    public void reconnect(String deviceNo) {
        // 指数退避：1s→2s→4s→8s→16s（上限 60s），最多 5 次后上抛
        long backoffMs = 1000;
        int maxAttempts = 5;
        String cert = "CERT-SIM-" + deviceNo;
        for (int i = 1; i <= maxAttempts; i++) {
            driver.markAttempt(deviceNo);
            SimulatedDriver.Result r = driver.roundTrip(cert, encodeConnect(cert));
            if (r.ok()) {
                return;
            }
            try {
                Thread.sleep(Math.min(backoffMs, 60_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException(409, "MQTT 重连被中断");
            }
            backoffMs *= 2;
        }
        throw new ServiceException(409, "MQTT 重连失败（已尝试 " + maxAttempts + " 次退避）："
                + deviceNo);
    }
}
