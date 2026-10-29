package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 系统月度 SLA 报告（add-sla-report-console，spec sla-report-management，
 * BR-5.6-16 / C-5.6-05 / C-0-01）：指标推导生成 → 归档锁定 → 发布门禁。
 */
public interface SlaReportService {

    /**
     * 按月生成/重生成报告（design D3 幂等分层）：
     * 无则 INSERT DRAFT；有且 DRAFT → 覆盖指标；有且 ARCHIVED/PUBLISHED → 422 锁定。
     *
     * @param period YYYY-MM
     * @return 报告摘要 {id, period, status, metricsCount, dataAsOf}
     */
    Map<String, Object> generateMonth(String period);

    /**
     * 月度 sweep：目标=上月，任意已存在状态即跳过（补缺不覆盖，D3）。
     *
     * @return {generated, skipped}
     */
    Map<String, Object> sweepGenerate();

    /** 归档（非 DRAFT 422；DRAFT → ARCHIVED + 版本锁定 + 审计） */
    void archive(String id);

    /**
     * 对外发布（D4 双层）：非 ARCHIVED → REQUIRES_NEW 违规留痕
     * （SECURITY_EVENT 审计 + 通知生成人与内审）后抛 422「报告未归档，不可发布」；
     * ARCHIVED → PUBLISHED + 留痕 + 审计；PUBLISHED → 422 重复发布。
     */
    void publish(String id);

    /**
     * CSV 下载（仅 ARCHIVED/PUBLISHED，DRAFT 422；固定列序 + UTF-8 BOM；
     * 下载动作审计留痕）。
     *
     * @return {filename, content bytes}
     */
    Map<String, Object> download(String id);

    /** 报告列表（period/status 过滤，分页倒序；含指标 JSON） */
    Map<String, Object> list(String period, String status, Integer page, Integer size);
}
