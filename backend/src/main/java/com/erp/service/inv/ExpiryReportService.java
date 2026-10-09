package com.erp.service.inv;

import java.util.List;
import java.util.Map;

/**
 * 每日效期预警报告（4.10.1，spec expiry-management 需求①，FR-4.4-3-6）：
 * 扫描后生成当日报告（黄橙红三级计数+明细+较前报增减），REPORT_DATE 唯一幂等覆盖，
 * 推送 ROLE_WAREHOUSE；失败推 ROLE_INTF_OPS。
 */
public interface ExpiryReportService {

    /**
     * 生成当日报告（幂等：按 REPORT_DATE 覆盖）。
     * 与最近一份报告 diff 新增/解除；首日无基线置 noBaseline 标记。
     * 生成成功推送 ROLE_WAREHOUSE，异常上抛由调度侧捕获推运维。
     */
    Map<String, Object> generateDailyReport();

    /** 历史报告列表（时间倒序，含三级计数，不含明细） */
    List<Map<String, Object>> history(long size);

    /** 按日报告详情（含明细与增减清单）；不存在返回 null */
    Map<String, Object> detail(String reportDate);
}
