package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账单明细行（task 10.7：未核销逐笔 + 差异逐笔排查记录）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_ar_statement_line")
public class ArStatementLine extends BaseEntity {

    public static final String CHK_PENDING = "PENDING";
    public static final String CHK_CHECKED = "CHECKED";
    public static final String CHK_DIFF = "DIFF";

    private String stmtId;
    private String arId;
    private String arNo;
    private String invoiceNo;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private BigDecimal amount;
    private BigDecimal paidAmount;
    private BigDecimal balance;
    private BigDecimal writeoffAmt;
    private Integer overdueDays;
    private BigDecimal diffAmt;
    private String checkStatus;
    private String checkNote;
    private String checkBy;
    private LocalDateTime checkAt;
}
