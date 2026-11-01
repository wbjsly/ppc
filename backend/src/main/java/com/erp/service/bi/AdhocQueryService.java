package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 即席查询服务（add-adhoc-query-console，spec bi-adhoc-query）。
 * 数据集选择（PUBLISHED 模板）、结构化查询（预检→同步/入队）、
 * 配额治理、我的查询。
 */
public interface AdhocQueryService {

    /**
     * 数据集列表：PUBLISHED 模板 × 行级权限可达；含字段树（维度/指标分组、
     * 未注册口径指标标注、timeFlag/roleFlag 语义）。
     */
    List<Map<String, Object>> datasets();

    /**
     * 发起查询（主入口，design D3）：
     * 构建 → 预检（BR-4.10-30 时间/组织过滤硬阻断）→ 成本预估 →
     * 同步执行或入队（BR-4.10-31 / 配额 BR-4.10-33）。
     *
     * @return {mode: "sync"|"queued", taskId, headers?, rows?, truncated?, quotaWarn?}
     */
    Map<String, Object> query(String templateId, String queryJson);

    /** 我的即席任务（含 REJECTED 阻断留痕） */
    List<Map<String, Object>> myTasks();

    /** 任务详情 + 结果集分页 */
    Map<String, Object> taskDetail(String taskId, int page, int size);

    /** 用量治理：配额余量 + 按用户汇总（14.6.2） */
    Map<String, Object> governance();

    /**
     * 按指定身份重跑查询定义（临时报表打开/共享接收人消费，spec bi-temp-report）：
     * 治理执行（行级注入+掩码+超时）并分页返回；不落任务行（非即席发起，不计配额）。
     */
    Map<String, Object> rerunFor(String templateId, String queryJson, String asUser,
                                 int page, int size);

    // ---------- 我的查询 ----------

    List<Map<String, Object>> savedQueries();

    Map<String, Object> saveQuery(Map<String, Object> payload);

    void deleteSavedQuery(String id);
}
