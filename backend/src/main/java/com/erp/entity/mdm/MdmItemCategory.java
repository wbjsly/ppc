package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 物料分类（种子即编码规则权威来源；1.2.3 分类维护菜单后续补管理界面）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_item_category")
public class MdmItemCategory extends BaseEntity {

    /** 4 位分类码，如 FG0001 */
    private String categoryCode;

    private String categoryName;

    /** 编码前缀：FG / RM / WIP */
    private String itemPrefix;

    private String parentId;

    private Integer level;

    private String status;

    /** 变更/合并/迁移原因（1.2.3 必填），非持久化，写入版本记录 */
    @TableField(exist = false)
    private transient String changeReason;

    /** 树形展示用子节点，非持久化 */
    @TableField(exist = false)
    private java.util.List<MdmItemCategory> children;
}
