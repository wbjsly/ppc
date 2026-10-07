package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 预付款申请（2.7.3，spec prepayment，BR-4.2-52 / C-4.2-13）。
 * 双 L1：超预付比例 / 累计预付超 PO 未清金额；到货开票后按最早未核销顺序冲抵应付。
 */
@Getter
@Setter
@TableName("erp_fin_prepayment")
public class FinPrepayment extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING_APPROVE";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_PAID = "PAID";
    public static final String ST_CLOSED = "CLOSED";

    /** 预付单号 PP+yyyyMMdd+流水 */
    private String ppNo;
    private String poId;
    private String poNo;
    private String supplierId;
    private String supplierName;
    /** 本次所用预付比例 */
    private BigDecimal prepayRatio;
    private BigDecimal applyAmount;
    /** PO 总金额（校验快照） */
    private BigDecimal poTotalAmt;
    /** PO 未清金额（校验快照） */
    private BigDecimal poOpenAmt;
    private String status;
    /** 累计已付预付款 */
    private BigDecimal executedAmount;
    /** 已冲抵应付金额 */
    private BigDecimal settledAmount;
    private String approvalId;
    private String rejectReason;
    private String remark;
}
