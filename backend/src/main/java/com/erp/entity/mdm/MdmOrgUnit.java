package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 组织单元（基础数据-组织管理 1.1.4，表 erp_mdm_org_unit，3 级树 + 六类类型）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_org_unit")
public class MdmOrgUnit extends BaseEntity {

    /** OU-{类型码}-{2位流水}，创建后不可修改（C-4.1-01） */
    private String ouCode;

    private String ouName;

    /** 隶属法人主体 */
    private String legalEntityId;

    /** 父节点 ID，根节点为空串 */
    private String parentId;

    /** 1/2/3，深度上限 3（DC-06 成环校验同时生效） */
    private Integer treeLevel;

    /** 六类：FACTORY/WAREHOUSE/PROC/SALE/STORE/RD（类型不锁层级） */
    private String ouType;

    private String ownerName;

    private String remark;

    /** 1 启用 / 0 停用（C-4.1-03 仅停用，禁止物理删除） */
    private String status;

    /** 树形展示用子节点，非持久化 */
    @TableField(exist = false)
    private List<MdmOrgUnit> children;
}
