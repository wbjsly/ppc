package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 报表 SLA 服务报告（add-bi-sla-report，spec bi-sla-report，4.10 流程五）。
 * 五指标月度采集（快照固化+部分数据标注）→ 报告编制（未达标三要素）→
 * 三方审核归档（版本锁定）→ 归档后发布（未归档硬阻断）。与 16.9 完全隔离。
 */
public interface BiSlaReportService {

    /** 采集某月五指标快照（幂等 upsert）；供次月 1 日 cron 与手动生成调用 */
    Map<String, Object> collectMetrics(String period);

    /** 指标快照（五指标当月值+趋势近 6 月） */
    Map<String, Object> metrics(String period);

    /** 报告列表（月份/状态/版本/生成时间） */
    List<Map<String, Object>> listReports();

    /** 报告详情（含指标概览/三要素/改进措施/版本历史） */
    Map<String, Object> getReport(String period);

    /** 生成报告（采集+组 METRICS_JSON 三色，初始 DRAFT；仅 DRAFT 缺失时创建） */
    Map<String, Object> generateReport(String period);

    /** 编辑未达标三要素（ROOT_CAUSE_JSON；仅 DRAFT） */
    void saveRootCause(String period, List<Map<String, Object>> items);

    /** 编辑改进措施与下月计划（仅 DRAFT） */
    void saveImprove(String period, Map<String, Object> improve);

    /** 提交审核（BR-4.10-27：未达标三要素齐备否则 422；三方串行 ApprovalEngine） */
    Map<String, Object> submit(String period);

    /** 归档后修改 → 新版本行（VERSION+1 DRAFT，原行保留） */
    Map<String, Object> newVersion(String period);

    /** 对外发布（BR-4.10-26：非 ARCHIVED → 422+违规留痕+通知；成功→水印 PDF+渠道推送+PUBLISHED） */
    Map<String, Object> publish(String period);

    /** 报告 PDF 内容（水印 PDF 字节路径引用；仅 ARCHIVED/PUBLISHED） */
    Map<String, Object> pdf(String period);

    // ---- 调度入口 ----

    /** 次月 1 日 03:30 采集上月五指标 */
    void monthlyCollect();
}
