package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 职责分离 SoD 互斥规则（add-permission-approval-flow design D4，BR-5.6-02）。
 * 角色对互斥：ROLE_A < ROLE_B 规范化存储，UNIQUE(ROLE_A, ROLE_B)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_admin_sod_rule")
public class SodRule extends BaseEntity {

    /** 互斥角色 A（规范序） */
    @TableField("ROLE_A")
    private String roleA;

    /** 互斥角色 B（规范序） */
    @TableField("ROLE_B")
    private String roleB;

    /** 互斥说明 */
    @TableField("REMARK")
    private String remark;
}
