package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工单 BOM 快照行（change add-work-order-management，FR-4.5-3-3 / BR-4.5-16；迁移 120）。
 * 创建时一次性拷贝已发布 BOM（含替代料），源 BOM 后续变更不回写——快照隔离。
 * 行级不提供编辑（与头同生命周期）；行表用普通索引（沿 117 逻辑删除唯一键教训）。
 */
@Getter
@Setter
@TableName("erp_mrp_mo_bom")
public class MrpMoBom extends BaseEntity {

    private String moId;
    private Integer lineNo;
    /** BOM 层级（1=产品直接子项；多层展平 design D4） */
    private Integer treeLevel;
    /** 所属父件编码（本行挂在哪个件下） */
    private String ownerItem;
    private String itemCode;
    private String itemName;
    /** 单位用量（展平行为相对工单产品的有效单耗=链上乘积） */
    private BigDecimal unitQty;
    /** 损耗率（展平行为有效损耗 Π(1+损耗)−1，保持 REQ=QTY×用量×(1+损耗) 口径） */
    private BigDecimal lossRate;
    private String baseUnit;
    /** 替代料信息快照（JSON：编码/比例/优先级） */
    private String substituteInfo;
}
