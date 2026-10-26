package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 差旅报销单（add-cost-collection-freeze，FR-4.8-5-3）。 */
@Getter
@Setter
@TableName("erp_pm_travel_claim")
public class PmTravelClaim extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    /** 报销单号 DTC+yyyyMMdd-序号 */
    private String claimNo;
    private String empId;
    private String empName;
    /** 职级快照 */
    private String grade;
    /** 可空 → DEPT_ONLY */
    private String projectId;
    private String wbsId;
    /** 部门（DEPT_ONLY 归集主体） */
    private String deptId;
    /** 1=未挂项目，归部门费用 */
    private String deptOnly;
    private java.time.LocalDate startDate;
    private java.time.LocalDate endDate;
    private java.math.BigDecimal days;
    /** 标准快照 */
    private java.math.BigDecimal dailyLimit;
    private java.math.BigDecimal amount;
    /** 1=超标 */
    private String overStandardFlag;
    /** 超标必填 */
    private String overReason;
    /** L2 审批实例（超标） */
    private String overApprId;
    /** DRAFT|SUBMITTED|APPROVED|REJECTED */
    private String status;
    private String reviewBy;
    private java.time.LocalDateTime reviewDate;
    private String rejectReason;
    private String voucherId;
}
