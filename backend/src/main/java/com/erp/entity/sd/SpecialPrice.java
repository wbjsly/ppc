package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 特殊价格审批单（S-4.3-08 / FR-4.3-5-5 / BR-4.3-34）：
 * 最终价低于成本×(1+MIN_MARGIN_RATE) 时的放行凭据；
 * 审批走 approval-workflow（BIZ_TYPE=SpecialPrice，销售总监 + 财务会签）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_special_price")
public class SpecialPrice extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_EXPIRED = "EXPIRED";

    /** SP + yyyyMMdd + 4 位流水 */
    private String spNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    /** 空 = 该客户全部 SKU */
    private String itemCode;
    private BigDecimal originPrice;
    private BigDecimal specialPrice;
    private BigDecimal standardCost;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String reason;
    private String status;
    private String approvalId;
    private String decideBy;
    private LocalDateTime decideAt;
    private String opinion;
    private String applyBy;
}
