package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 预测维护（add-iot-mobile 13.8，FR-4.11-8-x / BR-4.11-48~53）：
 * 趋势告警消费评估、幂等建单（LINK_WO_ID=告警事件 ID）、误报率统计、闭环回写。
 */
public interface IotPredictService {

    /** 待评估趋势告警（TREND 且未建单） */
    List<Map<String, Object>> trendCandidates();

    /**
     * 幂等建单（BR-4.11-53 / C-4.11-10）：幂等键=告警事件 ID。
     * 已建单 → {duplicate:true, woNo}（409 幂等语义由调用方映射）。
     */
    Map<String, Object> createWoIdempotent(String eventNo);

    /** 误报率统计（30 天窗口，>20% 提示复核阈值，BR-4.11-52） */
    Map<String, Object> falsePositiveRate();

    /** 误报标记（工单判定非故障 → TREND 告警置位） */
    Map<String, Object> markFalsePositive(String eventNo, boolean fp);

    /** 工单关闭 → 告警事件闭环回写（BR-4.11-53） */
    Map<String, Object> onWoClosed(String woNo);
}
