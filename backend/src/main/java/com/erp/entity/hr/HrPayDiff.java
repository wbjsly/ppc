package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 薪酬发放差异处理单（5 工作日时限；金额调整 L2）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_pay_diff")
public class HrPayDiff extends BaseEntity {
    public static final String TYPE_RETURNED = "RETURNED";
    public static final String TYPE_MISMATCH = "AMOUNT_MISMATCH";
    public static final String TYPE_UNMATCHED = "UNMATCHED";
    public static final String TYPE_FAILED = "FAILED";

    public static final String PLAN_RESEND = "RESEND";
    public static final String PLAN_NEXT_MONTH = "NEXT_MONTH";
    public static final String PLAN_MANUAL = "MANUAL";

    public static final String ST_OPEN = "OPEN";
    public static final String ST_RESOLVED = "RESOLVED";
    public static final String ST_CLOSED = "CLOSED";

    private String diffNo;
    private String runId;
    private String period;
    private String empId;
    private String diffType;
    private BigDecimal expectAmount;
    private BigDecimal actualAmount;
    private String resolvePlan;
    private String resolveNote;
    private String status;
    private LocalDate deadline;
    private String apprBy;
    private LocalDateTime apprAt;
}
