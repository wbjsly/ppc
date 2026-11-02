package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 测点时序写入与查询（MySQL 月分区表，原始 90 天/聚合 3 年保留策略）。
 */
public interface IotTelemetryService {

    /** 批量写入测点（仿真器/网关共用入口） */
    int insertBatch(List<Map<String, Object>> points);

    /** 手动注入（演示/测试造数，带 batchTag） */
    int inject(Map<String, Object> body);

    /**
     * 时序查询：设备 + 测点 + 时间窗（自动裁剪到保留窗内）。
     *
     * @return [{ts, deviceNo, pointCode, val, quality}]
     */
    List<Map<String, Object>> query(String deviceNo, String pointCode,
                                    String start, String end, Integer limit);

    /** 可疑数据清单（QUALITY=SUSPECT） */
    List<Map<String, Object>> suspectList(String deviceNo, Integer limit);

    /** 最新测点（看板消费） */
    Map<String, Object> latest(String deviceNo, String pointCode);

    /** 归档维护：滚动补建下月分区 + 清理超 90 天原始分区（3 年聚合保留） */
    Map<String, Object> maintain();
}
