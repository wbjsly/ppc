package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 应收核销/回款记录（第 6 组提前建）。
 * 及时率（BR-4.3-15）与连续逾期（BR-4.3-16）的统计源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_ar_writeoff")
public class ArWriteoff extends BaseEntity {

    private String woNo;
    private String arId;
    private String arNo;
    private String customerId;
    private BigDecimal amount;
    private LocalDate payDate;
    private LocalDate dueDate;
    /** 1 = 按时（PAY_DATE ≤ DUE_DATE） */
    private String onTime;
    /** MANUAL / AUTO（FIFO 第 10 组） */
    private String writeType;
    /** 回款单关联（10.6） */
    private String receiptId;
    private String receiptNo;
    /** 核销凭证 ID（10.6 生成核销凭证） */
    private String voucherId;
    private String remark;
}
