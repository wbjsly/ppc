package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限矩阵覆盖层（add-role-permission-console design D2）：
 * 仅存差异格，绝对指令语义——ADD=强制包含 / REMOVE=强制排除，
 * 与 099 基线在读取时合并（基线 ∪ override），清除=物理删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_menu_perm_override")
public class MenuPermOverride extends BaseEntity {

    public static final String ACT_ADD = "ADD";
    public static final String ACT_REMOVE = "REMOVE";

    /** 菜单节点 ID（erp_admin_menu.ID） */
    @TableField("MENU_ID")
    private String menuId;

    /** 角色码 */
    @TableField("ROLE_CODE")
    private String roleCode;

    /** ADD=强制包含 / REMOVE=强制排除 */
    @TableField("ACTION")
    private String action;
}
