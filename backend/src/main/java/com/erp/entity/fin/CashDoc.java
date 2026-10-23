package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 统一收付款单（M8-4-1/8.4-2，spec payment-collection）：
 * 方向 IN 客户回款 / OUT 供应商付款，一个内核（design D1）；旧 ar_receipt/fin_payment 迁移合并。
 */
@Getter
@Setter
@TableName("erp_fin_cash_doc")
public class CashDoc extends BaseEntity {

    public static final String DIR_IN = "IN";
    public static final String DIR_OUT = "OUT";
    public static final String PARTY_CUSTOMER = "CUSTOMER";
    public static final String PARTY_SUPPLIER = "SUPPLIER";
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING_POST = "PENDING_POST";
    public static final String ST_POSTED = "POSTED";
    public static final String ST_RECONCILED = "RECONCILED";
    public static final String ST_VOID = "VOID";
    public static final String WO_NONE = "NONE";
    public static final String WO_PENDING = "PENDING";
    public static final String WO_APPROVED = "APPROVED";
    public static final String WO_DONE = "DONE";
    public static final String WO_REJECTED = "REJECTED";
    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_PREPAY = "PREPAY";

    /** IN: RC+yyyyMMdd+流水 / OUT: PY+yyyyMMdd+流水 */
    private String docNo;
    private String direction;
    private String partyType;
    private String partyId;
    private String partyCode;
    private String partyName;
    /** 单据金额 */
    private BigDecimal amount;
    /** SCAR 抵扣合计（OUT 应付侧） */
    private BigDecimal deductAmount;
    /** 实收/实付 = AMOUNT − DEDUCT（银行余额变动额） */
    private BigDecimal actualAmount;
    private String currency;
    /** 电汇/支票/商业汇票/银行承兑汇票/现金（BR-4.6-20） */
    private String payMethod;
    private String bankAccountId;
    private String accountName;
    private String accountNo;
    /** 收付款日期（凭证日期，BR-4.6-19） */
    private LocalDate payDate;
    private String status;
    /** 关联付款/预付申请（OUT 侧，可空） */
    private String reqId;
    private String reqNo;
    /** PAYMENT 应付 / PREPAY 预付（OUT 侧凭证分流） */
    private String payType;
    /** 抵扣扣款单 ID 集合 JSON */
    private String deductIds;
    /** 过账资金凭证 ID（RCT/PAY/PPR） */
    private String voucherId;
    /** 1=跨期核销（C-4.6-02） */
    private String crossPeriod;
    private String writeoffState;
    /** 跨期核销审批实例锚 */
    private String apprId;
    private String rejectReason;
    private String remark;
}
