package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 数据质量规则（add-data-extract-console design D1/D4，spec bi-quality-check）。
 * NOT_NULL 必填非空率 / RECONCILE 跨表勾稽 / RANGE 数值范围；
 * SEVERE 阻断批次就绪、WARN 告警放行（BR-4.10-14）。
 */
@Getter
@Setter
@TableName("erp_bi_quality_rule")
public class BiQualityRule extends BaseEntity {

    public static final String TYPE_NOT_NULL = "NOT_NULL";
    public static final String TYPE_RECONCILE = "RECONCILE";
    public static final String TYPE_RANGE = "RANGE";

    public static final String LVL_SEVERE = "SEVERE";
    public static final String LVL_WARN = "WARN";

    public static final String ST_ENABLED = "ENABLED";
    public static final String ST_DISABLED = "DISABLED";

    private String name;
    private String ruleType;
    private String datasourceId;
    private String targetTable;
    private String targetColumn;
    private String leftSql;
    private String rightSql;
    /** 非空率阈值百分比（默认 99.900） */
    private BigDecimal notNullMin;
    /** 勾稽容差（|左-右| ≤ 容差 通过） */
    private BigDecimal tolerance;
    private BigDecimal minVal;
    private BigDecimal maxVal;
    private String severeLevel;
    private String status;
    private String remark;
}
