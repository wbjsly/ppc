package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 客户回款登记（task 10.6，spec FIFO 自动核销输入）。
 * 到账后按最早未核销应收优先匹配；无法匹配的余额转人工核销队列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_ar_receipt")
public class ArReceipt extends BaseEntity {

    /** 全额自动匹配 */
    public static final String ST_AUTO = "AUTO";
    /** 部分匹配，余量待人工 */
    public static final String ST_PARTIAL = "PARTIAL";
    /** 无法匹配（无未清应收）转人工 */
    public static final String ST_PENDING = "PENDING";
    /** 人工核销完成 */
    public static final String ST_MANUAL = "MANUAL";

    private String rcptNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private BigDecimal amount;
    private LocalDate payDate;
    private BigDecimal matchedAmt;
    private BigDecimal unmatchedAmt;
    private String status;
    private String voucherId;
    private String remark;
}
