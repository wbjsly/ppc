package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 移动端异常上报与联动（13.4，spec iot-mobile FR-4.11-3-x / BR-4.11-19~24）。
 * 三类：QUALITY→即时质量冻结 / EQUIPMENT→EAM 维修工单+设备故障态 /
 * SAFETY→网关停机指令打点。IoT 自动上报须现场确认门禁；同班次≥3 同类合并升级。
 */
public interface IotExceptionService {

    /** 三类异常上报 + 联动编排（紧急级即时待办；IoT 来源待确认不触发重动作） */
    Map<String, Object> report(Map<String, Object> body);

    /** IoT 自动上报现场确认（CONFIRM_FLAG=1 + 触发被门禁的联动） */
    Map<String, Object> confirm(String id);

    /** 处置闭环（PROCESSING→CLOSED，联动单状态回写） */
    Map<String, Object> dispose(String id, String result, String remark);

    /** 异常单查询 */
    List<Map<String, Object>> list(String status, String excType);

    /**
     * 30min 响应升级扫描（BR-4.11-22）：OPEN 且 DUE_AT 过期 →
     * 升级设备主管/安全总监待办。
     */
    Map<String, Object> scanEscalate();
}
