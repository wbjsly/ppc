package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 季度销售目标（客户 × 季度，tasks 11.2，spec sales-rebate 三配置之一）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_rebate_target")
public class RebateTarget extends BaseEntity {

    private String customerId;
    private String customerCode;
    private String customerName;
    /** 如 2026Q4 */
    private String quarter;
    private BigDecimal targetAmt;
    private String remark;
}
