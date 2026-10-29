package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 权限授予台账（add-permission-approval-flow design D1/D6）：
 * 生命周期事实源（申请→授予→到期→回收），APPLY_ID = C-5.6-01 变更锚点；
 * erp_admin_user_role 仅作生效投影。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_grant_ledger")
public class GrantLedger extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_RECLAIMED = "RECLAIMED";
    public static final String ST_EXPIRED = "EXPIRED";

    /** 来源申请单（C-5.6-01 锚点） */
    @TableField("APPLY_ID")
    private String applyId;

    /** 被授予用户 */
    @TableField("TARGET_USER_ID")
    private String targetUserId;

    /** 授予角色 */
    @TableField("ROLE_CODE")
    private String roleCode;

    /** NORMAL / EXCEPTION */
    @TableField("SOURCE")
    private String source;

    @TableField("GRANT_AT")
    private LocalDateTime grantAt;

    @TableField("EXPIRE_AT")
    private LocalDateTime expireAt;

    /** ACTIVE / RECLAIMED / EXPIRED */
    @TableField("STATUS")
    private String status;

    @TableField("RECLAIM_AT")
    private LocalDateTime reclaimAt;

    /** 回收前权限快照（该用户当时角色全集 JSON） */
    @TableField("SNAPSHOT_JSON")
    private String snapshotJson;

    /** 到期前提醒时间（7 日提醒幂等标记） */
    @TableField("REMIND_AT")
    private LocalDateTime remindAt;
}
