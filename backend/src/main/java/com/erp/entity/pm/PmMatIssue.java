package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 项目领料单（add-cost-collection-freeze，FR-4.8-5-1）。 */
@Getter
@Setter
@TableName("erp_pm_mat_issue")
public class PmMatIssue extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SUBMITTED = "SUBMITTED";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    /** 领料单号 PMI+yyyyMMdd-序号 */
    private String issueNo;
    private String projectId;
    /** 领用日期 */
    private java.time.LocalDate issueDate;
    private String remark;
    /** DRAFT|SUBMITTED|APPROVED|REJECTED */
    private String status;
    private String reviewBy;
    private java.time.LocalDateTime reviewDate;
    private String rejectReason;
    /** 会计凭证回写 */
    private String voucherId;

    /** 1=研发试制（BR-4.8-37 与量产分账，须关联 TRIAL 任务；add-rnd-material） */
    private String trialFlag;
    /** 试制用途说明 */
    private String trialPurpose;
}
