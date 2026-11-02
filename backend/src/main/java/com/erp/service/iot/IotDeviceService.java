package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * IoT 设备接入与协议配置（FR-4.11-5-1，C-4.11-18 证书无效 L1 拒绝）。
 */
public interface IotDeviceService {

    /** 设备注册/接入配置（协议/采集频率/数据点映射/告警规则） */
    Map<String, Object> register(Map<String, Object> body);

    /** 连接测试（经网关适配器握手；证书无效 L1 拒绝） */
    Map<String, Object> connectTest(String deviceNo);

    /** 心跳打点 */
    Map<String, Object> heartbeat(String deviceNo);

    /** 设备列表 */
    List<Map<String, Object>> list(String keyword, String status, String deviceType);

    /** 设备详情 */
    Map<String, Object> detail(String deviceNo);

    /** 状态分布（13.6.4 运行/停机/故障/维护） */
    Map<String, Object> statusDistribution();

    /** 更新告警规则 */
    Map<String, Object> updateAlertRule(String deviceNo, String alertRuleJson);
}
