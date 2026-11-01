package com.erp.service.bi;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 报表数据抽取（add-data-extract-console，spec bi-extract-console）：
 * 逻辑数据源登记 + 模板驱动配置 + 批次执行（DAG/波动/断点/就绪）。
 */
public interface DataExtractService {

    // ---------- 数据源（14.2.1，FR-4.10-2-1 / BR-4.10-15）----------

    List<Map<String, Object>> listDatasources();

    Map<String, Object> createDatasource(Map<String, Object> payload);

    Map<String, Object> updateDatasource(String id, Map<String, Object> payload);

    void deleteDatasource(String id);

    /** 连通性测试：DB=本库可达+登记名核对；API/FILE=仅登记形态提示 */
    Map<String, Object> testConnection(String id);

    /** schema 快照重采与对比：不一致 → SCHEMA_CHANGED + 暂停其下配置自动执行 */
    Map<String, Object> checkSchema(String id);

    /** 更新快照并恢复 ACTIVE（映射更新并校验通过后恢复，BR-4.10-15） */
    Map<String, Object> refreshSnapshot(String id);

    // ---------- 抽取配置 ----------

    List<Map<String, Object>> listConfigs(String datasourceId);

    Map<String, Object> createConfig(Map<String, Object> payload);

    Map<String, Object> updateConfig(String id, Map<String, Object> payload);

    void deleteConfig(String id);

    // ---------- 批次执行（BR-4.10-12/13）----------

    /** 批次任务列表（不存在则按当日 ENABLED 配置生成） */
    List<Map<String, Object>> batchRuns(LocalDate date);

    /** 执行/推进批次（manual=手动补跑绕过窗口） */
    Map<String, Object> runBatch(LocalDate date, boolean manual);

    /** 波动暂停人工确认继续 */
    Map<String, Object> confirmRun(String runId);

    /** 暂停任务人工放弃（终止留痕） */
    Map<String, Object> abortRun(String runId);

    /** 数据就绪状态（依赖方前置） */
    Map<String, Object> readySnapshot();
}
