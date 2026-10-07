package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 年度返利预算（年度总额 + 按季度分解，tasks 11.2/11.5，spec sales-rebate FR-4.3-8-3）。
 * 季度预算余额 = 该季额度 − 该季结算单（非草稿/驳回）累计返利。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_rebate_budget")
public class RebateBudget extends BaseEntity {

    private Integer budgetYear;
    private BigDecimal totalAmt;
    private BigDecimal q1Amt;
    private BigDecimal q2Amt;
    private BigDecimal q3Amt;
    private BigDecimal q4Amt;
    private String remark;
}
