package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_role")
public class SysRole extends BaseEntity {

    @TableField("ROLE_CODE")
    private String roleCode;

    @TableField("ROLE_NAME")
    private String roleName;

    @TableField("SORT_ORDER")
    private Integer sortOrder;

    /** 1 启用 / 0 禁用 */
    @TableField("STATUS")
    private String status;
}
