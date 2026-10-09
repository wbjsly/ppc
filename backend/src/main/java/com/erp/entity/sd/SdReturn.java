package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售退货申请（tasks 12.1 提前建；9.8 拒收生成退货申请草稿的载体）。
 * 12 组补判定（责任方/超期/可退量）、红字发票关联与换货执行列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_return")
public class SdReturn extends BaseEntity {

    public static final String SRC_CUSTOMER = "CUSTOMER";
    /** 客户拒收转入（自动关联原发货单） */
    public static final String SRC_REJECT = "REJECT";

    public static final String HANDLE_REFUND = "REFUND";
    public static final String HANDLE_EXCHANGE = "EXCHANGE";

    public static final String INV_UNINVOICED = "UNINVOICED";
    public static final String INV_INVOICED = "INVOICED";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_JUDGED = "JUDGED";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_DONE = "DONE";
    public static final String ST_CANCELLED = "CANCELLED";

    private String returnNo;
    private String sourceType;
    private String shipId;
    private String shipNo;
    private String soId;
    private String soNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private BigDecimal totalQty;
    private BigDecimal totalAmt;
    private String handleType;
    private String returnReason;
    private String reasonType;
    /** 开票状态分流：未开票 / 已开票 */
    private String invoiceFlag;
    private String status;
    private String approvalId;
    private String applyBy;
    private LocalDateTime applyAt;
    private String remark;

    // ---------- 12 组判定与执行扩展（057 动态列） ----------

    /** 判定人（12.3） */
    private String judgeBy;
    private LocalDateTime judgeAt;
    /** 判定依据 */
    private String judgeNote;
    /** 责任方：OUR_QUALITY 我方质量 / CUSTOMER 客户原因 / LOGISTICS 物流破损 */
    private String liability;
    /** 超期标记（申请超过 RETURN_PERIOD_DAYS） */
    private String overdue;
    private String overdueNote;
    /** 判定处理方式 REFUND/EXCHANGE（判定可修正申请的期望方式） */
    private String judgeHandle;
    private LocalDateTime submitAt;
    /** 累计已退款 */
    private BigDecimal refundAmt;
    private LocalDateTime refundAt;
    private String refundBy;
    /** 退款支付登记号（已收款部分退款留痕） */
    private String refundPayNo;
    /** 红字发票（已开票退款回填） */
    private String redInvoiceId;
    private String redInvoiceNo;
    /** 红冲凭证 / 退货入库凭证 */
    private String voucherId;
    private String voucherNo;
    /** 换货发货单（12.6） */
    private String exchangeShipId;
    private String exchangeShipNo;
    /** 实物入库完成（12.7） */
    private String stockIn;
    private LocalDateTime stockInAt;
    private String stockInBy;
}
