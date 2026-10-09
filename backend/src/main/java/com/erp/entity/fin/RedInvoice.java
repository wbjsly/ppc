package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 红字发票台账（task 10.5，spec 红字发票与应收红冲 D14）。
 * 关联原发票编号与退货单，红冲后应收余额相应减少并进入核销匹配池。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_red_invoice")
public class RedInvoice extends BaseEntity {

    public static final String ST_ISSUED = "ISSUED";

    private String redNo;
    private String originInvoiceId;
    private String originInvoiceNo;
    private String arId;
    private String arNo;
    private String returnId;
    private String returnNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private LocalDate redDate;
    private BigDecimal amount;
    private BigDecimal netAmount;
    private BigDecimal taxRate;
    private BigDecimal taxAmount;
    private String reason;
    private String status;
    private String issueBy;
    private LocalDateTime issueAt;
    private String remark;
}
