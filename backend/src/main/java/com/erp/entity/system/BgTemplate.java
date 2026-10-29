package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 紧急权限模板（add-break-glass-console design D6，SOP-5.6-C step2）。
 * 角色集合为最小可用集合，保存时禁特权角色（PrivilegedRoles 校验）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_bg_template")
public class BgTemplate extends BaseEntity {

    @TableField("CODE")
    private String code;

    @TableField("NAME")
    private String name;

    /** 最小可用角色集合 JSON 数组 */
    @TableField("ROLES_JSON")
    private String rolesJson;

    @TableField("SCENARIO")
    private String scenario;

    /** '1' 启用 / '0' 停用 */
    @TableField("STATUS")
    private String status;
}
