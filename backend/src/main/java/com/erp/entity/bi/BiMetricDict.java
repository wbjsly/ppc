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
    /** 变更审批中（add-metric-dict-console 状态机） */
    public static final String ST_CHANGE_PENDING = "CHANGE_PENDING";
    /** 灰度期：新版未生效，旧版仍 IS_CURRENT（BR-4.10-04） */
    public static final String ST_GRAYING = "GRAYING";
    /** 灰度终止：旧版续用，新版废弃留痕 */
    public static final String ST_ABORTED = "ABORTED";

    /** 公式类型（spec 6.3.1 十二要素） */
    public static final String FT_RATIO = "RATIO";
    public static final String FT_PRODUCT = "PRODUCT";
    public static final String FT_ACCUM = "ACCUM";

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
    /** 十二要素扩展（add-metric-dict-console design D3） */
    private String formulaType;
    private String numerator;
    private String denominator;
    private String dimensions;
    private String owner;
    private Integer version;
    private Boolean isCurrent;
    private String status;
    /** 引用方清单 JSON（反向扫描对象） */
    private String usedBy;
    private String remark;
}
