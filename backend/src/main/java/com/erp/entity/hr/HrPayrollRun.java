package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** PayrollRun 批次（生成后明细冻结；审批过锁定；BR-4.7-13）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_payroll_run")
public class HrPayrollRun extends BaseEntity {
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTING = "SUBMITTING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_VOID = "VOID";
    public static final String ST_PAID = "PAID";
    public static final String ST_PARTIAL = "PARTIAL";
    public static final String ST_RECONCILED = "RECONCILED";
    public static final String ST_ARCHIVED = "ARCHIVED";

    public static final String TYPE_NORMAL = "NORMAL";
    public static final String TYPE_CORRECTION = "CORRECTION";

    /** PR+yyyyMMdd-序号 */
    private String runNo;
    /** 核算期 yyyyMM */
    private String period;
    private Integer empCount;
    private BigDecimal grossTotal;
    private BigDecimal deductTotal;
    private BigDecimal netTotal;
    private String status;
    private String voidReason;
    private LocalDateTime submitAt;
    private LocalDateTime approveAt;
    private String approveBy;
    private String approveOpinion;
    /** 更正单关联原批次 */
    private String parentRunId;
    private String runType;
}
