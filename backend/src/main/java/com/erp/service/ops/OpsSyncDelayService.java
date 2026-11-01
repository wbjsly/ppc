package com.erp.service.ops;

import java.util.List;
import java.util.Map;

/**
 * 同步延迟采集（18.2.1，BR-5.8-11）：订单/库存/对账三类事件
 * outbox OCCURRED_AT → delivery ACK 时差聚合，超阈三级告警 + 趋势。
 */
public interface OpsSyncDelayService {

    /**
     * 采集一轮三类事件的端到端同步延迟：
     * 按事件类型前缀（SO_/STOCK./FIN 对账）聚合近 24h 已 ACK 投递的时差均值/P95；
     * 超 SLA_SYNC_THRESHOLD（默认 5 分钟）→ raise 告警（Warning/Critical/Emergency 分级）。
     *
     * @return 采集行数
     */
    int collect();

    /** 延迟趋势（近 N 天，18.2.3） */
    List<Map<String, Object>> trend(int days);
}
