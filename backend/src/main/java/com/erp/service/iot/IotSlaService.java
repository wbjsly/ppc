package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 移动/IoT SLA 月度报告（add-iot-mobile 13.9，FR-4.11-6-x / C-05 未归档不发布）。
 * 五指标：可用率 / 同步成功率 / 平均响应时间 / 离线作业占比 / 告警响应。
 */
public interface IotSlaService {

    /** 生成月度报告（DRAFT，五指标真实统计快照） */
    Map<String, Object> generate(String period);

    /** 归档（状态机 DRAFT→ARCHIVED；归档自动推送订阅人，13.9.2） */
    Map<String, Object> archive(String period);

    /** 发布（未归档 422 阻断，C-05；归档后 ARCHIVED→PUBLISHED） */
    Map<String, Object> publish(String period);

    /** 报告列表 */
    List<Map<String, Object>> list();

    /** 报告订阅（13.9.2，防重复推送） */
    Map<String, Object> subscribe(String subscriber);
}
