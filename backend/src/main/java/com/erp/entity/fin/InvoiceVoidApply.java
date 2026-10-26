package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 发票作废申请单（M9-4-2，spec red-invoice-void）。
 * 三条件（BR-4.14-24：当月开具 + 未交付 + 购方未确认）在申请时逐项校验，任一不满足 422 阻断引导红冲；
 * 审批接 ApprovalEngine（C-4.14-03 留痕），通过后执行作废置 VOID（终态，不可恢复）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_invoice_void_apply")
public class InvoiceVoidApply extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVED = "APPROVED";
    /** 已执行作废（发票 VOID + 申请终态） */
    public static final String ST_VOIDED = "VOIDED";
    public static final String ST_REJECTED = "REJECTED";

    /** 申请单号 VSAP+yyyyMMdd+NNNN */
    private String applyNo;
    private String invoiceId;
    private String invoiceNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private BigDecimal amount;
    private String reason;
    private String status;
    private String apprId;
    private String voucherId;
    private String remark;
}
