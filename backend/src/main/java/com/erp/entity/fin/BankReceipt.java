package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 银行回单（M8-4-3 回单对账，spec bank-receipt-reconciliation，design D2/D8）：
 * 逐笔匹配 cash_doc；流水数据源为 M8-7 银行对账预留。
 */
@Getter
@Setter
@TableName("erp_fin_bank_receipt")
public class BankReceipt extends BaseEntity {

    public static final String DIR_RECEIVE = "RECEIVE";
    public static final String DIR_PAY = "PAY";
    public static final String SRC_IMPORT = "IMPORT";
    public static final String SRC_MANUAL = "MANUAL";
    public static final String ST_UNMATCHED = "UNMATCHED";
    public static final String ST_MATCHED = "MATCHED";
    public static final String ST_EXCEPTION = "EXCEPTION";

    /** 银行回单号（唯一） */
    private String receiptNo;
    private String bankAccountId;
    private LocalDate tradeDate;
    private String direction;
    private BigDecimal amount;
    /** 对方户名 */
    private String counterparty;
    private String summary;
    private String source;
    private String matchStatus;
    /** 匹配的收付款单 ID */
    private String matchedDocId;
    private String matchedDocNo;
    private String matchedBy;
    private LocalDateTime matchedAt;
    private String exceptionReason;
    /** 解除匹配操作人 */
    private String reconcileBy;
    private LocalDateTime reconcileAt;
    private String reconcileReason;
    private String remark;
}
