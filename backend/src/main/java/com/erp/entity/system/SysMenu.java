package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统菜单（层次化导航，见 docs/design/08-erp-menu.md）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_menu")
public class SysMenu extends BaseEntity {

    /** 上级菜单 ID，顶级为空字符串 */
    @TableField("PARENT_ID")
    private String parentId;

    /** 菜单编号（08 文档清单编号，如 16.2.1，用于需求追溯） */
    @TableField("MENU_CODE")
    private String menuCode;

    @TableField("TITLE")
    private String title;

    /** 路由路径；目录节点为空 */
    @TableField("PATH")
    private String path;

    @TableField("ICON")
    private String icon;

    @TableField("SORT_ORDER")
    private Integer sortOrder;

    /** 可见角色编码，逗号分隔；空表示所有登录用户可见 */
    @TableField("PERM")
    private String perm;

    /** 1 启用 / 0 禁用 */
    @TableField("STATUS")
    private String status;
}
