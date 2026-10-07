package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 核销/冲抵记录（2.7.3，design D5）：KIND=PAYMENT 付款核销 / SETTLE 预付冲抵，唯一键防重复。 */
@Getter
@Setter
@TableName("erp_fin_payment_writeoff")
public class FinPaymentWriteoff extends BaseEntity {

    public static final String KIND_PAYMENT = "PAYMENT";
    public static final String KIND_SETTLE = "SETTLE";

    private String kind;
    /** 付款单 ID（KIND=PAYMENT） */
    private String paymentId;
    /** 预付单 ID（KIND=SETTLE） */
    private String ppId;
    private String invoiceId;
    private String invoiceNo;
    private String supplierId;
    private String poNo;
    private BigDecimal amount;
    private LocalDate writeoffDate;
}
