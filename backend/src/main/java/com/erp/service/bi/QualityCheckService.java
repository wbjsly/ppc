package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 数据质量校验（add-data-extract-console，spec bi-quality-check，BR-4.10-14）：
 * 三类规则配置化 + SEVERE/WARN 分级处置 + 批次联动。
 */
public interface QualityCheckService {

    List<Map<String, Object>> listRules(String status);

    Map<String, Object> createRule(Map<String, Object> payload);

    Map<String, Object> updateRule(String id, Map<String, Object> payload);

    void deleteRule(String id);

    /**
     * 执行单条规则（手动「立即校验」，batchNo 空），落报告并返回本次结果。
     * 批次阻断解除请用 recheckBlockedBatch。
     */
    Map<String, Object> runNow(String ruleId);

    /**
     * 批次 READY 后自动执行（引擎调用）：跑 datasourceIds 范围内全部 ENABLED 规则。
     *
     * @return SEVERE 级 FAIL 条数（>0 → 调用方置批次 QUALITY_BLOCKED）
     */
    int runForBatch(String batchNo, List<String> datasourceIds);

    /**
     * QUALITY_BLOCKED 批次复验：重跑该批次全部规则，全部无 SEVERE FAIL
     * 则自动置批次 READY（design D4）。
     */
    Map<String, Object> recheckBlockedBatch(String batchNo);

    /** 校验报告查询（batchNo / 时间范围） */
    List<Map<String, Object>> listResults(String batchNo, String from, String to);
}
