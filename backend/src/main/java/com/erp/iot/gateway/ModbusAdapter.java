package com.erp.iot.gateway;

import com.erp.common.ServiceException;

import java.util.Map;

/**
 * Modbus 协议适配器（FR-4.11-5-1，MODBUS_TCP / MODBUS_RTU）：
 * TCP ADU（事务ID+协议ID+长度+单元ID+功能码）与 RTU 帧（地址+功能码+数据+CRC16）
 * 真实编解码；功能码最高位（|0x80）= 异常响应。
 */
public class ModbusAdapter implements DeviceGatewayAdapter {

    public static final String TCP = "MODBUS_TCP";
    public static final String RTU = "MODBUS_RTU";

    private final SimulatedDriver driver;
    private final String variant;

    public ModbusAdapter(SimulatedDriver driver, String variant) {
        this.driver = driver;
        this.variant = RTU.equals(variant) ? RTU : TCP;
    }

    @Override
    public String protocol() {
        return variant;
    }

    /** TCP ADU 编码：事务ID(2) 协议ID=0(2) 长度(2) 单元ID(1) 功能码(1) 数据 */
    public byte[] encodeReadHolding(int transId, int unitId, int startAddr, int qty) {
        byte[] data = new byte[4];
        data[0] = (byte) (startAddr >> 8);
        data[1] = (byte) startAddr;
        data[2] = (byte) (qty >> 8);
        data[3] = (byte) qty;
        if (RTU.equals(variant)) {
            // RTU：单元ID(1) 功能码(1) 数据(4) CRC16(2)
            byte[] frame = new byte[8];
            frame[0] = (byte) unitId;
            frame[1] = 0x03;
            System.arraycopy(data, 0, frame, 2, 4);
            int crc = crc16(frame, 6);
            frame[6] = (byte) (crc & 0xFF);
            frame[7] = (byte) ((crc >> 8) & 0xFF);
            return frame;
        }
        int len = 1 + 1 + data.length;          // 单元ID + 功能码 + 数据
        byte[] frame = new byte[6 + len];
        frame[0] = (byte) (transId >> 8);
        frame[1] = (byte) transId;
        frame[2] = 0;
        frame[3] = 0;                           // 协议 ID = 0
        frame[4] = (byte) (len >> 8);
        frame[5] = (byte) len;
        frame[6] = (byte) unitId;
        frame[7] = 0x03;
        System.arraycopy(data, 0, frame, 8, data.length);
        return frame;
    }

    /** 响应解码：异常响应（功能码|0x80）→ 异常码；否则取寄存器字节数 */
    public Map<String, Object> decodeResponse(byte[] frame) {
        if (frame == null || frame.length < 9) {
            throw new ServiceException(409, "Modbus 响应帧过短");
        }
        int func;
        if (RTU.equals(variant)) {
            func = frame[1] & 0xFF;
        } else {
            func = frame[7] & 0xFF;
        }
        if ((func & 0x80) != 0) {
            int code = RTU.equals(variant) ? (frame[2] & 0xFF) : (frame[8] & 0xFF);
            throw new ServiceException(409, "Modbus 异常响应：功能码 0x"
                    + Integer.toHexString(func) + " 异常码 " + code);
        }
        return Map.of("ok", true, "func", func);
    }

    /** CRC16（Modbus 多项式 0xA001 反射） */
    public static int crc16(byte[] data, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc >>= 1;
                }
            }
        }
        return crc;
    }

    @Override
    public Map<String, Object> handshake(String certSn, String deviceNo) {
        // Modbus 无应用层握手：以读保持寄存器探测 + 证书校验代替
        SimulatedDriver.Result r = driver.roundTrip(certSn,
                encodeReadHolding(1, 1, 0, 1));
        if (!r.ok()) {
            throw new ServiceException(409, variant + " 探测失败：" + r.error());
        }
        decodeResponse(r.frame());
        return Map.of("handshake", true, "protocol", variant,
                "sessionKey", variant + "-SESS-" + deviceNo, "ackMs", r.ackMs());
    }

    @Override
    public Map<String, Object> send(String deviceNo, String command, Map<String, Object> params) {
        SimulatedDriver.Result r = driver.roundTrip("CERT-SIM-" + deviceNo,
                encodeReadHolding(2, 1, 0, 1));
        if (!r.ok()) {
            throw new ServiceException(409, variant + " 指令下发失败：" + r.error());
        }
        decodeResponse(r.frame());
        return Map.of("ack", true, "ackMs", r.ackMs());
    }

    @Override
    public void subscribe(String deviceNo, PointConsumer consumer) {
        // 轮询式采集由 IotTelemetrySimulator 调度
    }

    @Override
    public void reconnect(String deviceNo) {
        long backoffMs = 1000;
        String cert = "CERT-SIM-" + deviceNo;
        for (int i = 1; i <= 5; i++) {
            driver.markAttempt(deviceNo);
            if (driver.roundTrip(cert, encodeReadHolding(1, 1, 0, 1)).ok()) {
                return;
            }
            try {
                Thread.sleep(Math.min(backoffMs, 60_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException(409, variant + " 重连被中断");
            }
            backoffMs *= 2;
        }
        throw new ServiceException(409, variant + " 重连失败（5 次退避）：" + deviceNo);
    }
}
