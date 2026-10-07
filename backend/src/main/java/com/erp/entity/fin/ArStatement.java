package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 月度应收对账单头（task 10.7，FR-4.3-7-6）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_ar_statement")
public class ArStatement extends BaseEntity {

    public static final String ST_GENERATED = "GENERATED";
    public static final String ST_CONFIRMED = "CONFIRMED";

    private String stmtNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    /** 对账期间 yyyyMM */
    private String period;
    private BigDecimal openBal;
    private BigDecimal invoiceAmt;
    private BigDecimal writeoffAmt;
    private BigDecimal closeBal;
    private Integer unpaidCnt;
    private String status;
    private String confirmBy;
    private java.time.LocalDateTime confirmAt;
    private String remark;
}
