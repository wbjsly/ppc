package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 岗位权限基线（add-role-permission-console design D1/D5）：
 * 系统域自有表，替代 hr_position.ROLES_JSON 作为建号/调岗角色映射唯一来源；
 * 新值含特权角色 → L1 硬阻断（PrivilegedRoles）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_position_baseline")
public class PositionBaseline extends BaseEntity {

    /** 岗位 ID（hr_position.ID） */
    @TableField("POS_ID")
    private String posId;

    /** 角色码 JSON 数组（如 ["ROLE_PLANNER"]），仅非特权角色 */
    @TableField("ROLE_CODES")
    private String roleCodes;
}
