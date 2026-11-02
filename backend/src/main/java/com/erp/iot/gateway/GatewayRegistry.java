package com.erp.iot.gateway;

import com.erp.common.ServiceException;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 网关适配器注册中心（FR-4.11-5-1 协议优先级：OPC-UA > MQTT > Modbus TCP > RTU）。
 * 按设备配置的协议路由到对应适配器；未知协议 422。
 */
@Component
public class GatewayRegistry {

    private final Map<String, DeviceGatewayAdapter> adapters;

    public GatewayRegistry(SimulatedDriver driver) {
        OpcUaAdapter opc = new OpcUaAdapter(driver);
        MqttAdapter mqtt = new MqttAdapter(driver);
        ModbusAdapter tcp = new ModbusAdapter(driver, ModbusAdapter.TCP);
        ModbusAdapter rtu = new ModbusAdapter(driver, ModbusAdapter.RTU);
        this.adapters = Map.of(
                opc.protocol(), opc,
                mqtt.protocol(), mqtt,
                tcp.protocol(), tcp,
                rtu.protocol(), rtu);
    }

    /** 按协议取适配器（OPC-UA/MQTT/MODBUS_TCP/MODBUS_RTU） */
    public DeviceGatewayAdapter byProtocol(String protocol) {
        DeviceGatewayAdapter a = adapters.get(protocol);
        if (a == null) {
            throw new ServiceException(422, "不支持的接入协议：" + protocol
                    + "（可选 OPC-UA/MQTT/MODBUS_TCP/MODBUS_RTU）");
        }
        return a;
    }

    /** 全部适配器（连接测试/状态页遍历） */
    public Map<String, DeviceGatewayAdapter> all() {
        return adapters;
    }
}
