package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 银行回单（手工登记；逐笔匹配对账）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_pay_receipt")
public class HrPayReceipt extends BaseEntity {
    public static final String ST_UNMATCHED = "UNMATCHED";
    public static final String ST_MATCHED = "MATCHED";

    private String receiptNo;
    private String runId;
    private String disbId;
    private LocalDate receiptDate;
    private BigDecimal amount;
    private String summary;
    private String matchStatus;
    private String matchItemId;
}
