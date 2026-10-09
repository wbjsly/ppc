package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 开票申请单（tasks 10.2~10.5，spec sales-invoicing-receivable，design D9 按次开票）。
 * 发货确认自动生成（金额 = 该次发货额，与发货单一一对应）；
 * 链路：提交（税务资质 C-4.3-06 + 容差校验）→ 财务审核 → 开具（外部系统桩）→ 客户确认/异议 → 红字。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_invoice_apply")
public class InvoiceApply extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_AUDITING = "AUDITING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_RETURNED = "RETURNED";
    public static final String ST_ISSUED = "ISSUED";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_DISPUTED = "DISPUTED";
    public static final String ST_RED = "RED";
    public static final String ST_CANCELLED = "CANCELLED";

    public static final String TYPE_SPECIAL = "VAT_SPECIAL";
    public static final String TYPE_GENERAL = "VAT_GENERAL";

    private String applyNo;
    private String shipId;
    private String shipNo;
    private String soId;
    private String soNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    /** 该次发货金额（基准，容差对比对象） */
    private BigDecimal shipAmount;
    /** 申请开票金额（可手工调整，提交时按 TOLERANCE_DEFAULT 校验） */
    private BigDecimal applyAmount;
    private String taxCode;
    private BigDecimal taxRate;
    private BigDecimal netAmount;
    private BigDecimal taxAmount;
    /** 1 税务资质通过 / 0 未过（C-4.3-06） */
    private String taxQualified;
    private String taxFailReason;
    private String invoiceType;
    private String status;
    private String auditBy;
    private LocalDateTime auditAt;
    private String auditOpinion;
    private String issueBy;
    private LocalDateTime issueAt;
    private String invoiceId;
    private String invoiceNo;
    private String arId;
    private String arNo;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String disputeNote;
    private String remark;
}
