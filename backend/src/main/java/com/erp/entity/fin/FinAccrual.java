package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 应付暂估单（2.7.1，spec ap-accrual，design D1 头级单据）。
 * 明细按 GR_NO 反查 erp_proc_gr_line，不建行表；部分冲减用 OFFSETTED_AMOUNT 累加表达。
 */
@Getter
@Setter
@TableName("erp_fin_accrual")
public class FinAccrual extends BaseEntity {

    public static final String SRC_GR = "GR";
    public static final String SRC_VMI = "VMI_TRANSFER";
    public static final String ST_OPEN = "OPEN";
    public static final String ST_REVERSED = "REVERSED";
    public static final String REV_MANUAL = "MANUAL";
    public static final String REV_AUTO = "AUTO_MATCH";

    /** 暂估单号 AC+yyyyMMdd+流水 */
    private String accrualNo;
    /** GR / VMI_TRANSFER */
    private String sourceType;
    private String grNo;
    /** 入库凭证号（IV 锚点） */
    private String postingDocNo;
    private String poNo;
    private String supplierId;
    private String supplierName;
    /** 暂估金额 = Σ 容差内核销量 × PO 行单价（与入库凭证行金额一致） */
    private BigDecimal amount;
    /** 退货累计冲减金额 */
    private BigDecimal offsettedAmount;
    /** OPEN / REVERSED */
    private String status;
    /** MANUAL / AUTO_MATCH */
    private String reverseSource;
    private String reverseInvoiceNo;
    private String reverseReason;
    private String reverseBy;
    private LocalDateTime reverseAt;
    /** 暂估凭证 ID */
    private String voucherId;
    /** 合并迁移前源供应商编码快照（BR-4.1-28） */
    private String originalSupplierCode;
    /** 合并迁移批次号（NULL=未迁移） */
    private String migrationBatchNo;
    /** NULL=未迁移 / 0=挂起待财务确认 / 1=已确认（BR-4.1-29） */
    private Integer migrationConfirmed;
    private String migrationConfirmBy;
    private LocalDateTime migrationConfirmAt;
}
