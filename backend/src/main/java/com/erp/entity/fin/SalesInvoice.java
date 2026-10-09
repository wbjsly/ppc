package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销项发票台账（tasks 10.4/10.5，spec sales-invoicing-receivable）。
 * 外部开票系统按桩口径：本地建模台账 + 生成发票号并回写 SO 与应收。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_sales_invoice")
public class SalesInvoice extends BaseEntity {

    public static final String ST_ISSUED = "ISSUED";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_DISPUTED = "DISPUTED";
    public static final String ST_VOID = "VOID";

    private String invoiceNo;
    /** 外部开票系统流水（桩返回） */
    private String externalNo;
    private String applyId;
    private String applyNo;
    private String shipId;
    private String shipNo;
    private String soId;
    private String soNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private String taxNo;
    private String invoiceType;
    private LocalDate invoiceDate;
    private BigDecimal amount;
    private BigDecimal netAmount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private String status;
    private String arId;
    private String arNo;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String disputeNote;
    private BigDecimal redAmount;
    private String remark;
}
