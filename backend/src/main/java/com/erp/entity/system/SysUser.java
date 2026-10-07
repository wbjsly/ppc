package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_user")
public class SysUser extends BaseEntity {

    @TableField("USERNAME")
    private String username;

    @TableField("PASSWORD")
    private String password;

    @TableField("NICK_NAME")
    private String nickName;

    /** 1 启用 / 0 禁用 */
    @TableField("STATUS")
    private String status;

    /** 门户账号绑定供应商（ROLE_SUPPLIER 行级隔离锚点，add-supplier-portal-collaboration D1） */
    @TableField("SUPPLIER_ID")
    private String supplierId;
}
