package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 指标血缘与影响分析（add-metric-dict-console，spec bi-metric-lineage）。
 * 四层关系：上游表 ← 指标 ← 衍生指标 ← 引用报表；
 * 三来源采集（srcMapping 解析 / formula 匹配 + 手工绑定 / 模板反查）；
 * 快照与口径版本绑定（S-4.10-15）。
 */
public interface MetricLineageService {

    /**
     * 采集并生成血缘快照（幂等：先按 METRIC_KEY+版本清后插）。
     * 三来源：srcMapping 表名、formula 中已注册指标编码（衍生）、模板 METRICS_JSON 反查（引用）。
     */
    Map<String, Object> collectAndSnapshot(String metricKey, int lineageVersion);

    /** 当前图谱：取最新版本快照，无快照则现算返回（不落库） */
    Map<String, Object> graph(String metricKey);

    /** 历史版本图谱回溯（按口径版本号查询快照） */
    Map<String, Object> graphVersion(String metricKey, int version);

    /** 手工绑定关系（衍生指标声明等），写入指标当前版本快照 */
    Map<String, Object> bind(String metricKey, String nodeType, String nodeId, String nodeName);

    /** 变更影响报告：快照结构化视图（三层清单 + 高风险标注），审批环节查阅 */
    Map<String, Object> impactReport(String metricKey, int version);
}
