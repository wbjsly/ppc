package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 统一核销记录（spec payment-collection，design D2/D4）：
 * target 区分 AR/AP 发票；SETTLE 预付冲抵 DOC 为空。FIFO 内核单一事实源。
 */
@Getter
@Setter
@TableName("erp_fin_cash_writeoff")
public class CashWriteoff extends BaseEntity {

    public static final String TARGET_AR = "AR";
    public static final String TARGET_AP = "AP";
    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_MANUAL = "MANUAL";
    public static final String MODE_SETTLE = "SETTLE";

    private String woNo;
    /** 收付款单 ID（SETTLE 预付冲抵为空） */
    private String docId;
    private String docNo;
    private String targetType;
    /** 被核销发票 ID */
    private String targetId;
    private String targetNo;
    private BigDecimal amount;
    private String writeoffMode;
    private LocalDate writeoffDate;
    /** 客户维度（AR 核销/返利冲抵，信用统计口径） */
    private String customerId;
    /** 目标应收到期日（ON_TIME 判定基准） */
    private LocalDate dueDate;
    /** 1=按时（writeoffDate ≤ dueDate，BR-4.3-15/16） */
    private String onTime;
    /** 业务子类：RECEIPT/PAYMENT/REBATE 等（沿旧表口径） */
    private String writeType;
    private String remark;
}
