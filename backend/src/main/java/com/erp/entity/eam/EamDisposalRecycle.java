package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 残料回收明细（→ 一键入库 GR_ID 回写，BR-35 冲减损益，add-asset-disposal design D1/D4）。
 */
@Getter
@Setter
@TableName("erp_eam_disposal_recycle")
public class EamDisposalRecycle extends BaseEntity {

    @TableField("ORDER_ID")
    private String orderId;

    @TableField("ITEM_NAME")
    private String itemName;

    /** 回收物物料编码（入库载体，须有物料主数据；139 加列） */
    @TableField("ITEM_CODE")
    private String itemCode;

    @TableField("QTY")
    private BigDecimal qty;

    /** 估价（入库成本价） */
    @TableField("EST_PRICE")
    private BigDecimal estPrice;

    @TableField("GR_ID")
    private String grId;

    /** PENDING/POSTED */
    @TableField("GR_STATUS")
    private String grStatus;
}
