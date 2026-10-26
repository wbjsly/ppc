package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 里程碑评审记录（add-milestone-management，FR-4.8-3-3；只增不改，永久保留）。 */
@Getter
@Setter
@TableName("erp_pm_ms_review")
public class PmMsReview extends BaseEntity {

    /** 三态评审结果 */
    public static final String R_PASS = "PASS";
    public static final String R_CONDITIONAL = "CONDITIONAL";
    public static final String R_FAIL = "FAIL";

    /** 里程碑 ID */
    private String milestoneId;
    /** 结果 */
    private String result;
    /** 评审意见 */
    private String opinion;
    /** 整改项（CONDITIONAL 必填） */
    private String rectifyItem;
    /** 整改期限（CONDITIONAL 必填） */
    private LocalDate rectifyDue;
    /** 返工计划（FAIL 必填） */
    private String reworkPlan;
    /** 评审人 */
    private String reviewBy;
    /** 评审时间 */
    private LocalDateTime reviewDate;
}
