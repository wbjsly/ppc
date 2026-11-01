package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 日报定义（add-daily-report-subscription，spec bi-daily-report）。
 * METRIC_KEYS_JSON=[{"kpiKey":"...","title":"..."}]，kpiKey 引 14.7 erp_bi_kpi_def；
 * PUSH_TIME 缺省 07:30（服务端强制不早于就绪时间）。
 */
@Getter
@Setter
@TableName("erp_bi_daily_report_def")
public class DailyReportDef extends BaseEntity {

    public static final String ST_ENABLED = "ENABLED";
    public static final String ST_DISABLED = "DISABLED";

    private String code;
    private String name;
    private String metricKeysJson;
    private String pushTime;
    private String status;
    private String remark;
}
