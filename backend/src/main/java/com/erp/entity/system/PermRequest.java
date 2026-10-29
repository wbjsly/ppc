package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 权限申请单（add-permission-approval-flow design D1，SOP-5.6-B step1）。
 * KIND=NORMAL 常规 / EXCEPTION 超基线例外（吸收 RoleGrantException，D7）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_perm_request")
public class PermRequest extends BaseEntity {

    public static final String KIND_NORMAL = "NORMAL";
    public static final String KIND_EXCEPTION = "EXCEPTION";

    public static final String ST_PENDING = "PENDING_APPROVAL";
    public static final String ST_APPROVED_PENDING = "APPROVED_PENDING";
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_CANCELLED = "CANCELLED";
    public static final String ST_EXPIRED = "EXPIRED";

    public static final String MODE_IMMEDIATE = "IMMEDIATE";
    public static final String MODE_SCHEDULED = "SCHEDULED";

    public static final String RISK_NORMAL = "NORMAL";
    public static final String RISK_SENSITIVE = "SENSITIVE";
    public static final String RISK_PRIVILEGED = "PRIVILEGED";

    @TableField("KIND")
    private String kind;

    /** 被授权用户 */
    @TableField("TARGET_USER_ID")
    private String targetUserId;

    /** 申请人（可为管理员代提） */
    @TableField("APPLICANT_USER_ID")
    private String applicantUserId;

    /** 目标角色 JSON 数组 */
    @TableField("ROLES_JSON")
    private String rolesJson;

    /** NORMAL / SENSITIVE / PRIVILEGED（取目标角色最高） */
    @TableField("RISK_LEVEL")
    private String riskLevel;

    /** 数据范围（法人/组织单元/部门）必填留痕 */
    @TableField("DATA_SCOPE")
    private String dataScope;

    @TableField("REASON")
    private String reason;

    /** 特权申请必附故障单/项目编号 */
    @TableField("TICKET_NO")
    private String ticketNo;

    /** 有效期月数（≤12） */
    @TableField("VALID_MONTHS")
    private Integer validMonths;

    /** IMMEDIATE / SCHEDULED */
    @TableField("ACTIVATION_MODE")
    private String activationMode;

    @TableField("ACTIVATE_AT")
    private LocalDateTime activateAt;

    /** 提交时预检快照 JSON */
    @TableField("SOD_SNAPSHOT_JSON")
    private String sodSnapshotJson;

    @TableField("STATUS")
    private String status;

    @TableField("APPR_INSTANCE_ID")
    private String apprInstanceId;

    /** 窗口外变更标记（add-change-window-console D3：'1'=提交时窗口外，CCB 升级） */
    @TableField("OUT_WINDOW")
    private String outWindow;

    /** 生效后台账行 ID */
    @TableField("GRANT_ID")
    private String grantId;
}
