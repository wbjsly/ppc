package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_user")
public class SysUser extends BaseEntity {

    public static final String ST_ACTIVE = "1";
    public static final String ST_DISABLED = "0";
    public static final String ST_ARCHIVED = "2";

    @TableField("USERNAME")
    private String username;

    @TableField("PASSWORD")
    private String password;

    @TableField("NICK_NAME")
    private String nickName;

    /** '1' 启用 / '0' 停用 / '2' 归档（add-account-lifecycle-console D1 三态） */
    @TableField("STATUS")
    private String status;

    /** 门户账号绑定供应商（ROLE_SUPPLIER 行级隔离锚点，add-supplier-portal-collaboration D1） */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 手机号（双因子绑定预留，本期仅落库——偏差表） */
    @TableField("PHONE")
    private String phone;

    /** 最后登录时间 */
    @TableField("LAST_LOGIN_AT")
    private LocalDateTime lastLoginAt;

    /** 强制改密标记（'1'=首次登录/重置后须改密） */
    @TableField("MUST_CHANGE_PWD")
    private String mustChangePwd;

    /** 归档时间（STATUS='2'，归档不可恢复 C-0-05） */
    @TableField("ARCHIVED_AT")
    private LocalDateTime archivedAt;
}
