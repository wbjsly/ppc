package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 经营看板服务（add-kpi-dashboard，spec bi-kpi-dashboard）：
 * KPI 定义/目标维护、每日快照、达成概览三色、趋势序列、预警扫描。
 */
public interface KpiService {

    // ---------- KPI 定义与目标（14.7.1 配置） ----------

    List<Map<String, Object>> listDefs();

    Map<String, Object> saveDef(Map<String, Object> payload);

    Map<String, Object> updateDef(String id, Map<String, Object> payload);

    void deleteDef(String id);

    List<Map<String, Object>> listTargets(String kpiKey);

    Map<String, Object> saveTarget(Map<String, Object> payload);

    // ---------- 快照与预警 ----------

    /** 执行全部 ENABLED KPI 的快照（幂等 upsert）+ 预警扫描；返回汇总 */
    Map<String, Object> runSnapshot();

    // ---------- 看板查询（三叶子） ----------

    /** 14.7.1 达成概览（KPI 卡片：actual/target/ratio/status/drillId） */
    List<Map<String, Object>> summary();

    /** 14.7.2 趋势序列（近 N 日快照 + 当期目标） */
    Map<String, Object> trend(String kpiKey, String dimValue, int days);

    /** 14.7.3 预警列表（ACTIVE/RECOVERED） */
    List<Map<String, Object>> alerts(String status);
}
