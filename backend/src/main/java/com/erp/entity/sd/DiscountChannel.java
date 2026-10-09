package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 渠道折扣（三层之一，FR-4.3-5-1 / BR-4.3-32）。
 * 按客户主数据渠道属性匹配；渠道未维护时折扣置 0 并 L4 提示。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_discount_channel")
public class DiscountChannel extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_STOPPED = "STOPPED";

    /** DIRECT/DEALER/ECOM/KA（CUSTOMER_CHANNEL 字典） */
    private String channel;
    /** 折扣率（0.05 = 5%） */
    private BigDecimal discountRate;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String status;
    private String remark;
}
