package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 成本中心（基础数据-组织管理 1.1.2，表 erp_mdm_cost_center，3 级树形）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_cost_center")
public class MdmCostCenter extends BaseEntity {

    /** CC-{类型码}-{2位流水}，创建后不可修改（C-4.1-01） */
    private String ccCode;

    private String ccName;

    /** 隶属法人主体 */
    private String legalEntityId;

    /** 所属利润中心（可空），须与本节点同属一个法人主体 */
    private String profitCenterId;

    /** 父节点 ID，根节点为空串 */
    private String parentId;

    /** 1/2/3，深度上限 3（DC-06 成环校验同时生效） */
    private Integer treeLevel;

    /** 生产 PROD / 销售 SALES / 管理 ADMIN / 研发 RD */
    private String costType;

    /** 是否所属主体的默认中心（FR-4.6-5-4），每主体至多一个 */
    private String isDefault;

    private String ownerName;

    private String remark;

    /** 1 启用 / 0 停用（C-4.1-03 仅停用，禁止物理删除） */
    private String status;

    /** 树形展示用子节点，非持久化 */
    @TableField(exist = false)
    private List<MdmCostCenter> children;
}
