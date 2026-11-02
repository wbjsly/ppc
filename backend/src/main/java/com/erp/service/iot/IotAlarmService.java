package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 阈值告警与联动闭环（13.6.3，5min 合并窗口，确认-处理-关闭）。
 */
public interface IotAlarmService {

    /**
     * 阈值判定（测点入库后触发）：多级阈值 + 趋势斜率；
     * 同 MERGE_KEY 在 IOT_ALARM_MERGE_WINDOW_MIN 窗口内合并计数。
     */
    Map<String, Object> evaluate(String deviceNo, String pointCode, java.math.BigDecimal val);

    /** 告警列表 */
    List<Map<String, Object>> list(String status, String alertLevel, String deviceNo);

    /** 确认 */
    Map<String, Object> confirm(String eventNo, String remark);

    /** 处理中 */
    Map<String, Object> processing(String eventNo, String remark);

    /** 关闭（闭环） */
    Map<String, Object> close(String eventNo, String remark);

    /**
     * 级别联动：L1 → 停机指令（仿真通道打点 stopCmdAt/stopAckMs）；
     * L2 → 质量冻结；L3 → 仅日志。
     */
    Map<String, Object> linkage(String eventNo);

    /**
     * 超时未确认升级（IotEscalateScheduler 调用）：
     * 超 IOT_ALARM_MERGE_WINDOW_MIN×6 未确认 → 升级上级。
     */
    Map<String, Object> scanEscalate();
}
