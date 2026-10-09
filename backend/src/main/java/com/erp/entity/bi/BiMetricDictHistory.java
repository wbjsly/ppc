package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 口径字典历史版本视图（与 BiMetricDict 同表，task 2.1；查询用 IS_CURRENT=0 或按 VERSION 降序）。
 * design D4：字典任何修改保留历史版本可回溯。
 */
@Getter
@Setter
@TableName("erp_bi_metric_dict")
public class BiMetricDictHistory extends BaseEntity {

    private String metricKey;
    private String name;
    private String definition;
    private String formula;
    private Integer dataPrecision;
    private String timeDim;
    private String filterCond;
    private String srcMapping;
    private Integer version;
    private Boolean isCurrent;
    private String status;
    private String usedBy;
    private String remark;
}
