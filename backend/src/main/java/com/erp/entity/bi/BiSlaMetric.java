package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 报表 SLA 五指标月度快照（add-bi-sla-report，spec bi-sla-report）。
 * UNIQUE PERIOD+METRIC_KEY；PARTIAL_FLAG=Y 表示部分数据（BR-4.10-29 禁插值）。
 */
@Getter
@Setter
@TableName("erp_bi_sla_metric")
public class BiSlaMetric extends BaseEntity {

    /** 报表可用率（调度成功率代理，下限 %） */
    public static final String K_AVAILABILITY = "AVAILABILITY";
    /** 查询响应 P95（上限秒） */
    public static final String K_QUERY_P95 = "QUERY_P95";
    /** 数据刷新及时率（下限 %） */
    public static final String K_REFRESH_ONTIME = "REFRESH_ONTIME";
    /** 导出成功率（下限 %） */
    public static final String K_EXPORT_SUCCESS = "EXPORT_SUCCESS";
    /** 日报触达率（无固定目标，仅展示） */
    public static final String K_DAILY_REACH = "DAILY_REACH";

    private String period;
    private String metricKey;
    private BigDecimal metricValue;
    private BigDecimal target;
    private String unit;
    private String partialFlag;
    private String missingDates;
    private String errorMsg;
}
