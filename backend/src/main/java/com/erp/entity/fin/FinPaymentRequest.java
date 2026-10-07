package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 付款申请（2.7.3，spec payment-request）。
 * 状态机 DRAFT → PENDING_APPROVE → APPROVED | REJECTED → SCHEDULED | WAIT_FUNDS → PAID → CLOSED。
 */
@Getter
@Setter
@TableName("erp_fin_payment_request")
public class FinPaymentRequest extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING_APPROVE";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_SCHEDULED = "SCHEDULED";
    public static final String ST_WAIT_FUNDS = "WAIT_FUNDS";
    public static final String ST_PAID = "PAID";
    public static final String ST_CLOSED = "CLOSED";

    /** 申请单号 PA+yyyyMMdd+流水 */
    private String reqNo;
    private String supplierId;
    private String supplierName;
    private BigDecimal applyAmount;
    /** 关联发票 ID 集合（JSON 数组） */
    private String invoiceIds;
    private String status;
    /** 计划付款日 */
    private LocalDate planDate;
    private String scheduleBy;
    private LocalDateTime scheduleAt;
    /** 累计已执行付款 */
    private BigDecimal executedAmount;
    private String approvalId;
    private String rejectReason;
    private String remark;
}
