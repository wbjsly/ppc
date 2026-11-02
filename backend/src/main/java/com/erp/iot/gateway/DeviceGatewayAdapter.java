package com.erp.iot.gateway;

import java.util.Map;

/**
 * IoT 设备网关适配器（add-iot-mobile，FR-4.11-5-1，proposal 偏差 1：接口全真 + 仿真驱动）。
 * 协议适配器（MQTT/OPC-UA/Modbus）实现报文编解码、证书握手、断线重连的真实逻辑；
 * 物理链路由 {@link SimulatedDriver} 进程内承载（未来换真实 broker 仅替换 Driver）。
 */
public interface DeviceGatewayAdapter {

    /** 协议标识（MQTT / OPC-UA / MODBUS_TCP / MODBUS_RTU） */
    String protocol();

    /**
     * 证书握手：校验证书有效性（无效 → L1 拒绝，C-4.11-18）。
     *
     * @return {handshake:true, sessionKey}
     * @throws com.erp.common.ServiceException 409 证书无效/过期
     */
    Map<String, Object> handshake(String certSn, String deviceNo);

    /**
     * 下发采集/控制指令（停机指令走 CONTROL 类型，BR-4.11-21 时效打点）。
     *
     * @return {ack:true, ackMs, frame}
     */
    Map<String, Object> send(String deviceNo, String command, Map<String, Object> params);

    /** 订阅测点数据流（仿真驱动推送 → 回调写时序表） */
    void subscribe(String deviceNo, PointConsumer consumer);

    /** 断线重连（指数退避：1s→2s→4s…上限 60s，最多 5 次后上抛） */
    void reconnect(String deviceNo);

    /** 测点消费回调 */
    interface PointConsumer {
        void onPoint(String deviceNo, String pointCode, String value, boolean suspect);
    }
}
