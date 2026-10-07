package com.erp.service.intf;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.entity.intf.IntfSlaMetric;
import com.erp.entity.intf.IntfSlaReport;

import java.util.Map;

/** SLA 指标、告警升级与月报（spec interface-sla-monitoring，design D7）。 */
public interface SlaService {

    /** 一轮指标采集（每分钟）：三源聚合 + 三级告警 + 升级链；返回写入指标条数 */
    int collect();

    /** 月度报告生成（每月 1 日 02:00） */
    Map<String, Object> generateReport(String monthTag);

    /** 审核（审核人 ≠ 生成作业执行人，意见不可空） */
    Map<String, Object> reviewReport(String reportId, String opinion);

    /** 归档（高危：仅 ADMIN；生成版本号与时间戳，内容只读） */
    Map<String, Object> archiveReport(String reportId);

    /** 对外发布（高危：仅 ADMIN；未归档硬阻断 + 违规留痕） */
    Map<String, Object> publishReport(String reportId, String scope);

    Page<IntfSlaMetric> metrics(long current, long size, String metricKey, String monthTag);

    Page<IntfSlaAlert> alerts(long current, long size, String level, String status);

    /** 告警响应（记录响应人与时间，解除升级计时） */
    Map<String, Object> respondAlert(String alertId);

    Page<IntfSlaReport> reports(long current, long size, String status);
}
