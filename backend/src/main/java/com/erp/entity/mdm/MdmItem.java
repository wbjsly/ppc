package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 物料主数据（基础数据-物料管理 1.2.1，表 erp_mdm_item）。
 * 编码规则：{FG|RM|WIP} + 4位分类码 + 6位流水（BR-4.1-07），创建后不可修改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_item")
public class MdmItem extends BaseEntity {

    private String itemCode;

    private String itemName;

    /** 4 位分类码，关联 erp_mdm_item_category */
    private String categoryCode;

    /** 基本计量单位（字典 UNIT） */
    private String baseUnit;

    /** 物料组（字典 MATERIAL_GROUP） */
    private String materialGroup;

    /** BUY 外购 / MAKE 自制 / OUTSOURCE 委外 */
    private String purchaseType;

    /** NORMAL/CHILL/FROZEN/HAZARD（字典 STORAGE） */
    private String storageCondition;

    private String altItemCode;

    private BigDecimal safetyStock;

    private Integer leadTimeDays;

    /** 批次管理标识 1/0（BR-4.1-08：为 1 时保质期必填） */
    private String batchFlag;

    /** 原材料差异化字段：保质期天数 */
    private Integer shelfLifeDays;

    /** 成品差异化字段：包装规格 */
    private String packingSpec;

    /** 成品差异化字段：条码 */
    private String barcode;

    /** 自制件 BOM 版本号（BR-4.1-09：可后补） */
    private String bomVersion;

    /** 查重「确认非重复」差异说明（forceCreate 时必填） */
    private String dupNote;

    /** 1 启用 Active / 0 停用 Inactive / 2 已归档 Archived（C-4.1-03 仅停用/归档，禁止物理删除；归档为终态） */
    private String status;

    /** 自制件 BOM 待补录提示（BR-4.1-09 L4），仅响应携带，非持久化 */
    @TableField(exist = false)
    private transient Boolean bomPending;

    /** 变更原因（流程二 FR-4.1-2-1，UPDATE 时必填），非持久化，写入版本记录 */
    @TableField(exist = false)
    private transient String changeReason;
}
