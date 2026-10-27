package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 试制退料单（add-rnd-material，FR-4.8-10-4）。 */
@Getter
@Setter
@TableName("erp_pm_trial_return")
public class PmTrialReturn extends BaseEntity {

    /** RTR+yyyyMMdd-序号 */
    private String returnNo;
    /** 关联试制领料单 */
    private String issueId;
    private String projectId;
    /** DRAFT|SUBMITTED|APPROVED|REJECTED */
    private String status;
    private String reviewBy;
    private java.time.LocalDateTime reviewDate;
    private String rejectReason;
    /** 成本冲减凭证回写 */
    private String voucherId;

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
}
