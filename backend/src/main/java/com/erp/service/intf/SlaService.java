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

    /**
     * 月度报告生成（每月 1 日 02:00 调度传 "sla-job"；手动传当前操作人）。
     * operator 记入 generatedBy——使"审核人 ≠ 生成执行人"互斥对人类操作人真正生效
     * （add-service-monitoring-console，spec interface-sla-monitoring MODIFIED 月报 req）
     */
    Map<String, Object> generateReport(String monthTag, String operator);

    /** 审核（审核人 ≠ 生成作业执行人，意见不可空） */
    Map<String, Object> reviewReport(String reportId, String opinion);

    /** 归档（高危：仅 ADMIN；生成版本号与时间戳，内容只读） */
    Map<String, Object> archiveReport(String reportId);

    /** 对外发布（高危：仅 ADMIN；未归档硬阻断 + 违规留痕） */
    Map<String, Object> publishReport(String reportId, String scope);

    /**
     * 指标分页（spec service-monitoring-console 指标采集页）：
     * partnerCode=null 全部；空串=全局行（PARTNER_CODE 为空）；否则该伙伴行
     */
    Page<IntfSlaMetric> metrics(long current, long size, String metricKey, String monthTag,
                                String partnerCode);

    Page<IntfSlaAlert> alerts(long current, long size, String level, String status);

    /** 告警响应（记录响应人与时间，解除升级计时） */
    Map<String, Object> respondAlert(String alertId);

    Page<IntfSlaReport> reports(long current, long size, String status);
}
