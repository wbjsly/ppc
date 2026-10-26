package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 外派/借调单（secondment-management，FR-4.7-7；10.8.1/10.8.2 共用，CHG_TYPE 区分）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_secondment")
public class HrSecondment extends BaseEntity {
    public static final String TYPE_DISPATCH = "DISPATCH";
    public static final String TYPE_SECOND = "SECOND";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_CONFIRMED_OUT = "CONFIRMED_OUT";
    public static final String ST_CONFIRMED_IN = "CONFIRMED_IN";
    public static final String ST_HR_REVIEWED = "HR_REVIEWED";
    public static final String ST_REVIEWING = "REVIEWING";
    public static final String ST_REVIEW_DIFF = "REVIEW_DIFF";
    public static final String ST_APPROVING = "APPROVING";
    /** 已批待生效（到 START_DATE 由 effect/扫描执行） */
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_EFFECTED = "EFFECTED";
    public static final String ST_EXTENDING = "EXTENDING";
    public static final String ST_RETURNING = "RETURNING";
    public static final String ST_RETURNED = "RETURNED";
    public static final String ST_EXPIRED_ALERT = "EXPIRED_ALERT";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_VOID = "VOID";

    private String secNo;
    /** DISPATCH 外派 / SECOND 借调 */
    private String chgType;
    private String empId;
    private String empName;
    private String empNo;
    /** 调出主体（劳动关系主体） */
    private String srcLe;
    /** 用工/新主体 LE_CODE */
    private String targetLe;
    private String targetPlace;
    private String placeChange;
    /** 用工主体成本中心（借调）/新成本中心（外派） */
    private String costCenter;
    /** 返岗恢复快照 */
    private String origCostCenter;
    /** 薪酬发放主体（备案，恒=劳动关系主体） */
    private String salaryOwner;
    /** 分摊比例 0-100（借调必填，双方确认后锁定） */
    private BigDecimal costSharePct;
    private String shareLocked;
    private BigDecimal allowanceMonthly;
    private BigDecimal allowanceStdMonthly;
    /** 三方协议编号（借调必填 BR-4.7-19） */
    private String agreementNo;
    private LocalDate agreementDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private String remindFlag;
    private String status;
    private String confirmOutJson;
    private String confirmInJson;
    private String hrOpinion;
    private String review1Json;
    private String review1By;
    private LocalDateTime review1At;
    private String review2Json;
    private String review2By;
    private LocalDateTime review2At;
    private String reviewDiffJson;
    private String adjudicateNote;
    private String apprId;
    /** 累计延期月数（BR-4.7-05 上限校验） */
    private Integer extendMonthsTotal;
    private LocalDateTime effectiveAt;
    private LocalDateTime returnAt;
    private String linkPending;
    private String linkNote;
}
