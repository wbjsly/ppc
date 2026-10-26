package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 任务级预算行（project-initiation，FR-4.8-2-3；11.3.3）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_budget_line")
public class PmBudgetLine extends BaseEntity {

    private String projectId;
    /** 任务级（level3） */
    private String wbsId;
    /** 人工=工时×费率（服务端算） */
    private BigDecimal labor;
    private BigDecimal material;
    private BigDecimal subcontract;
    private BigDecimal travel;
    private BigDecimal other;
    /** 间接=前五项×分摊率（服务端算） */
    private BigDecimal indirect;
}
