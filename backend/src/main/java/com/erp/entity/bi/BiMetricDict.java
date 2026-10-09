package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 指标口径字典（spec bi-metric-dictionary，design D4：唯一权威，行级版本单表）。
 * 同 METRIC_KEY 多行：IS_CURRENT=1 为生效版本，其余为历史（History 实体读）。
 */
@Getter
@Setter
@TableName("erp_bi_metric_dict")
public class BiMetricDict extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_PENDING_UPDATE = "PENDING_UPDATE";

    private String metricKey;
    private String name;
    private String definition;
    /** 计算公式（JSON 结构化，diff 校验对象） */
    private String formula;
    private Integer dataPrecision;
    /** DAY / MONTH / QUARTER / YEAR */
    private String timeDim;
    private String filterCond;
    /** 来源字段映射 JSON：表.列 → 公式变量 */
    private String srcMapping;
    private Integer version;
    private Boolean isCurrent;
    private String status;
    /** 引用方清单 JSON（反向扫描对象） */
    private String usedBy;
    private String remark;
}
