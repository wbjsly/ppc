package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * BOM 行明细（change add-bom-management，spec bom-management FR-4.5-1-2；迁移 117）。
 * 用量 > 0、损耗率 0~100%（BR-4.5-10：MRP 实际需求 = 基础用量 ×(1+损耗率)）；
 * UOM 为子项物料单位快照（行级替代同单位校验基准，偏差 D2）。
 */
@Getter
@Setter
@TableName("erp_mrp_bom_item")
public class MrpBomItem extends BaseEntity {

    private String bomId;
    private Integer lineNo;
    private String itemCode;
    private String itemName;
    private BigDecimal qty;
    private BigDecimal lossRate;
    private String uom;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String remark;

    /** 行级替代料 1:N（FR-4.5-1-3）——非库字段，随行覆盖保存 */
    @TableField(exist = false)
    private List<MrpBomSubstitute> substitutes;
}
