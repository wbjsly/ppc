package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 会计凭证头（最小总账骨架，spec gl-voucher）。
 * 凭证号全局唯一自动生成（BR-4.6-07）；生成即 POSTED，已过账不可改仅红字冲销（BR-4.6-09）。
 */
@Getter
@Setter
@TableName("erp_fin_voucher")
public class FinVoucher extends BaseEntity {

    /** ACC 暂估 / AP 正式应付 / APD 应付借项 / REV 红字冲销 / PADJ 价差调整 */
    public static final String TYPE_ACCRUAL = "ACC";
    public static final String TYPE_AP = "AP";
    public static final String TYPE_AP_DEBIT = "APD";
    public static final String TYPE_REVERSE = "REV";
    public static final String TYPE_PRICE_ADJ = "PADJ";
    /** 付款域（add-payment-management）：PAY 应付付款 / PPR 预付付款 / PPO 预付冲抵 */
    public static final String TYPE_PAY = "PAY";
    public static final String TYPE_PREPAY = "PPR";
    public static final String TYPE_PREPAY_OFFSET = "PPO";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_POSTED = "POSTED";

    /** 凭证号（类型前缀 + 年月 + 流水） */
    private String voucherNo;
    private String voucherType;
    private LocalDate voucherDate;
    /** 会计期间 YYYYMM（自然月近似，design D8） */
    private String period;
    private String legalEntity;
    private String currency;
    private String summary;
    /** 来源单据类型：GR / RETURN / MATCH / REVERSE / VMI_TRANSFER / MIGRATION */
    private String sourceType;
    /** 来源单据号（业财锚点，业务单号 → 凭证反查） */
    private String sourceDocNo;
    /** DRAFT / POSTED */
    private String status;
    /** 红字冲销关联的原凭证 ID */
    private String reversesId;
    private BigDecimal totalDr;
    private BigDecimal totalCr;
}
