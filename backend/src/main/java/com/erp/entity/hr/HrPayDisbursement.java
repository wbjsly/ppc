package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 银行代发文件（已审批 PayrollRun → CSV；网银推送留桩=下载）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_pay_disbursement")
public class HrPayDisbursement extends BaseEntity {
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_VOID = "VOID";

    /** DF+yyyyMMdd-序号 */
    private String fileNo;
    private String runId;
    private String period;
    private Integer lineCount;
    /** 实发合计（须=批次实发） */
    private BigDecimal totalAmount;
    private String format;
    /** 文件内容（CSV 文本） */
    private String content;
    private String status;
    private String voidReason;
}
