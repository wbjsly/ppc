package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 三方匹配结果 / 异常对账单（2.7.2，spec three-way-match）。
 * MATCHED 已自动冲回 → EXCEPTION 冻结待确认 → CONFIRMED 已确认 → POSTED 已过账。
 */
@Getter
@Setter
@TableName("erp_fin_match_result")
public class FinMatchResult extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_MATCHED = "MATCHED";
    public static final String ST_EXCEPTION = "EXCEPTION";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_POSTED = "POSTED";
    public static final String ST_FAILED = "FAILED";

    public static final String EX_PRICE = "PRICE_DIFF";
    public static final String EX_QTY = "QTY_DIFF";
    public static final String EX_NO_RECEIPT = "NO_RECEIPT";

    /** 匹配单号 MT+yyyyMMdd+流水 */
    private String matchNo;
    private String invoiceId;
    private String invoiceNo;
    private String poNo;
    private String supplierId;
    private String supplierName;
    private String status;
    private BigDecimal diffQtyRate;
    private BigDecimal diffPriceRate;
    /** 发票额 − 暂估额 */
    private BigDecimal diffAmount;
    private BigDecimal diffAmountRate;
    /** 逐行差异明细（JSON） */
    private String detail;
    /** PRICE_DIFF / QTY_DIFF / NO_RECEIPT */
    private String exceptionType;
    /** 跨会计期间（发票自然月 < 当前自然月） */
    private Integer periodCross;
    private String confirmOpinion;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String postBy;
    private LocalDateTime postAt;
    /** 正式应付/价差调整凭证 ID */
    private String voucherId;
    private Integer accrualBatchReversed;
}
