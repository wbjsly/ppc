package com.erp.service.ops;

import java.util.List;
import java.util.Map;

/**
 * 容量采集与预警（18.4，spec ops-sla，BR-5.8-10）。
 */
public interface OpsCapacityService {

    /**
     * 采集一轮三指标（DB_SIZE/CONN_POOL/CPU）落库，并做连续 3 天超阈预警+外推。
     *
     * @return 采集到的指标数
     */
    int collect();

    /** 近 N 天容量指标（18.4.1 趋势） */
    List<Map<String, Object>> metrics(int days);
}
