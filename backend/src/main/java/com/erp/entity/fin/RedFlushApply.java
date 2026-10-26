package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 红冲申请单（M9-4-1，spec red-invoice-void，design D1）。
 * 状态机 DRAFT→APPROVED→INFO_ISSUED→ISSUED（终态）| REJECTED（驳回）。
 * 审批接 ApprovalEngine（分级 + 频繁红冲升级）；审批通过自动生成红字信息单
 * （填开方按购方抵扣状态判定 BR-4.14-25）；信息表编号录入后方可开红票（编号门禁）。
 * 原票已作废/已全额红冲 → 阻断申请（C-4.14-12/BR-4.14-28）；金额超剩余可红冲 → 阻断（C-4.14-02）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_red_flush_apply")
public class RedFlushApply extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_INFO_ISSUED = "INFO_ISSUED";
    public static final String ST_ISSUED = "ISSUED";
    public static final String ST_REJECTED = "REJECTED";

    public static final String REASON_RETURN = "RETURN";
    public static final String REASON_DISCOUNT = "DISCOUNT";
    public static final String REASON_ERROR = "ERROR";

    /** 信息表填开方：购方已认证抵扣 → 购方填开（BR-4.14-25） */
    public static final String FILLER_BUYER = "BUYER";
    public static final String FILLER_SELLER = "SELLER";

    /** 申请单号 FSAP+yyyyMMdd+NNNN */
    private String applyNo;
    private String originInvoiceId;
    private String originInvoiceNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    /** 红冲金额（价税合计） */
    private BigDecimal amount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private String reasonType;
    private String reason;
    private String status;
    /** ApprovalEngine 审批实例 ID */
    private String apprId;
    /** 红字信息表编号（开红票门禁） */
    private String infoSheetNo;
    /** 信息表填开方 BUYER/SELLER */
    private String infoSheetFiller;
    /** 1=跨期红冲（BR-4.14-27 留痕，审批意见须含更正申报评估） */
    private String crossPeriodFlag;
    private String crossPeriodNote;
    /** 1=频繁红冲升级（同客户窗口内超次数阈值，FR-4.14-4-2） */
    private String freqFlag;
    private String redInvoiceId;
    private String redNo;
    private String voucherId;
    /** 重开关联：由本单发起的新开票申请号（BR-4.14-29） */
    private String reopenApplyNo;
    private String remark;
}
