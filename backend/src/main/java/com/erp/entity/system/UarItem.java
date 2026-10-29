package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 复核项（账号×角色，add-access-review-console design D1，SOP-5.6-D step4）。
 * 快照生成时刻固化；复核人二态（按人/按角色回落）；判定两选 KEEP/RECLAIM。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_uar_item")
public class UarItem extends BaseEntity {

    public static final String J_KEEP = "KEEP";
    public static final String J_RECLAIM = "RECLAIM";

    public static final String EX_PENDING = "PENDING";
    public static final String EX_EXECUTED = "EXECUTED";
    public static final String EX_SKIPPED = "SKIPPED";

    @TableField("TASK_ID")
    private String taskId;

    @TableField("TARGET_USER_ID")
    private String targetUserId;

    @TableField("ROLE_CODE")
    private String roleCode;

    /** NORMAL/SENSITIVE/PRIVILEGED 静态清单映射 */
    @TableField("RISK_TAG")
    private String riskTag;

    @TableField("SNAPSHOT_JSON")
    private String snapshotJson;

    @TableField("REVIEWER_USER_ID")
    private String reviewerUserId;

    @TableField("REVIEWER_ROLE")
    private String reviewerRole;

    @TableField("JUDGMENT")
    private String judgment;

    @TableField("JUDGE_REASON")
    private String judgeReason;

    @TableField("JUDGED_AT")
    private LocalDateTime judgedAt;

    @TableField("EXEC_STATUS")
    private String execStatus;
}
