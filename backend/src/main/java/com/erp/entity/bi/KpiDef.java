package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 经营看板 KPI 定义（add-kpi-dashboard，spec bi-kpi-dashboard）。
 * METRIC_KEY 挂口径字典（可空=未注册宽容降级）；SQL_TEXT 为取数四列 SELECT
 * （kpi_key/dim_value/period_date/actual）；下钻挂 14.1 PUBLISHED 模板。
 */
@Getter
@Setter
@TableName("erp_bi_kpi_def")
public class KpiDef extends BaseEntity {

    public static final String MODE_FIXED = "FIXED";
    public static final String MODE_PERIODIC = "PERIODIC";
    public static final String ST_ENABLED = "ENABLED";
    public static final String ST_DISABLED = "DISABLED";

    private String kpiKey;
    private String name;
    private String metricKey;
    /** 维度列（可空=仅全局值） */
    private String dimKey;
    private String sqlText;
    private String drillTemplateId;
    private String targetMode;
    private BigDecimal fixedTarget;
    private BigDecimal thresholdWarn;
    private BigDecimal thresholdCritical;
    private String status;
    /** 责任人（快照执行身份） */
    private String owner;
    private String remark;
}
