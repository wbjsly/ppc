package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 付款单（2.7.3，spec payment-execution）：PAYMENT 应付付款 / PREPAY 预付付款，共用执行内核（design D4）。 */
@Getter
@Setter
@TableName("erp_fin_payment")
public class FinPayment extends BaseEntity {

    public static final String TYPE_PAYMENT = "PAYMENT";
    public static final String TYPE_PREPAY = "PREPAY";
    public static final String ST_PAID = "PAID";

    /** 付款单号 PY+yyyyMMdd+流水 */
    private String payNo;
    private String payType;
    /** 付款申请或预付款申请 ID */
    private String reqId;
    private String reqNo;
    private String supplierId;
    private String supplierName;
    /** 本次执行的申请金额（≤ 审批金额，L1） */
    private BigDecimal applyAmount;
    /** SCAR 抵扣合计 */
    private BigDecimal deductAmount;
    /** 实付 = 申请 − 抵扣（银行扣减额） */
    private BigDecimal actualAmount;
    /** 电汇/支票/商业汇票/银行承兑汇票/现金 */
    private String payMethod;
    private String bankAccountId;
    private String accountName;
    private String accountNo;
    private LocalDate payDate;
    private String status;
    /** 抵扣扣款单 ID 集合（JSON） */
    private String deductIds;
    private String voucherId;
    private String remark;
}
