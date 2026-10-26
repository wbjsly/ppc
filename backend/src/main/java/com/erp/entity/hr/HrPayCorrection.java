package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 薪酬更正申请（BR-4.7-13 L2：审批过生成正/负向更正批次关联原批次）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_pay_correction")
public class HrPayCorrection extends BaseEntity {
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_POSTED = "POSTED";

    private String corrNo;
    private String period;
    /** 原批次 */
    private String runId;
    private String empId;
    /** 调整项（如 OVERTIME_WAGE/IIT） */
    private String adjustItem;
    private BigDecimal adjustAmount;
    private String reason;
    private String status;
    private String apprBy;
    private LocalDateTime apprAt;
    private String apprOpinion;
    /** 生成的更正批次 */
    private String correctionRunId;
}
