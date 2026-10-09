package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 时间促销活动（三层之一，FR-4.3-5-3 / BR-4.3-33）。
 * 仅 PUBLISHED 且窗口内参与；同 SKU 重叠取折扣最大者，其余进未采用清单。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_promotion")
public class Promotion extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_STOPPED = "STOPPED";

    private String promoNo;
    private String promoName;
    private String itemCode;
    /** 促销折扣率（0.08 = 8%） */
    private BigDecimal discountRate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private String remark;
}
