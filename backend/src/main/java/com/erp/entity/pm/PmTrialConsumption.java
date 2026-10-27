package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 试制消耗确认单（add-rnd-material，FR-4.8-10-3，一领料单一份）。 */
@Getter
@Setter
@TableName("erp_pm_trial_consumption")
public class PmTrialConsumption extends BaseEntity {

    /** 关联试制领料单 */
    private String issueId;
    private String projectId;
    private String wbsId;
    /** 试制报告附件（BIZ_TYPE=TRIAL_REPORT） */
    private String reportAttachId;
    /** 领用数汇总快照 */
    private java.math.BigDecimal totalIssued;
    /** 实耗数汇总快照 */
    private java.math.BigDecimal totalActual;
    /** 结余数汇总快照 */
    private java.math.BigDecimal totalSurplus;
    /** 1=有差异>10% 未说明 */
    private String deviationFlag;
    private String remark;
    /** CONFIRMED（确认即终态，禁改） */
    private String status;
    private String confirmBy;
    private java.time.LocalDateTime confirmDate;

    public static final String ST_CONFIRMED = "CONFIRMED";
}
