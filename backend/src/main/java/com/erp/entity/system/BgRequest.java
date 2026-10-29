package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Break-Glass 申请单（add-break-glass-console design D1，SOP-5.6-C）。
 * 独立 bizType=BreakGlassRequest；生效豁免变更窗口（BG 即窗口外合规通道）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_bg_request")
public class BgRequest extends BaseEntity {

    public static final String ST_PENDING = "PENDING_APPROVAL";
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_RECLAIMED = "RECLAIMED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_AUDITED = "AUDITED";

    @TableField("TARGET_USER_ID")
    private String targetUserId;

    @TableField("APPLICANT_USER_ID")
    private String applicantUserId;

    @TableField("TEMPLATE_ID")
    private String templateId;

    /** 提交时模板角色快照 */
    @TableField("ROLES_JSON")
    private String rolesJson;

    @TableField("INCIDENT_ID")
    private String incidentId;

    @TableField("DURATION_HOURS")
    private Integer durationHours;

    /** YES/NO 录屏声明 */
    @TableField("SCREEN_DECLARE")
    private String screenDeclare;

    @TableField("REASON")
    private String reason;

    @TableField("STATUS")
    private String status;

    @TableField("GRANT_ID")
    private String grantId;

    @TableField("APPR_INSTANCE_ID")
    private String apprInstanceId;

    @TableField("RECLAIM_AT")
    private LocalDateTime reclaimAt;
}
