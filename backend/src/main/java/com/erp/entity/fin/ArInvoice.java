package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 应收账款头（第 6 组提前建，第 10 组扩展开票/核销联动）。
 * 账龄 = 未付部分的 DUE_DATE 距今天数；付款及时率取核销记录 PAY_DATE vs DUE_DATE。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_ar_invoice")
public class ArInvoice extends BaseEntity {

    public static final String ST_UNPAID = "UNPAID";
    public static final String ST_PARTIAL = "PARTIAL";
    public static final String ST_PAID = "PAID";
    public static final String ST_RED = "RED";

    private String arNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private String soId;
    private String soNo;
    /** 开票申请关联（tasks 10.2/10.4） */
    private String applyId;
    private String applyNo;
    /** 发货一一对应（发票与发货对应关系） */
    private String shipId;
    private String shipNo;
    private String invoiceId;
    /** 发票号回写（10.4） */
    private String invoiceNo;
    private String taxCode;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    /** 累计红字冲销额（10.5，余额 = AMOUNT − RED − PAID） */
    private BigDecimal redAmount;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private String status;
    private String remark;
}
