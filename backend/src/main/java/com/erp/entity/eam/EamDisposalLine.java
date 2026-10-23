package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 处置明细（一卡一行，批量多行；快照冻结比对基准，add-asset-disposal design D1）。
 */
@Getter
@Setter
@TableName("erp_eam_disposal_line")
public class EamDisposalLine extends BaseEntity {

    @TableField("ORDER_ID")
    private String orderId;

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    @TableField("ASSET_NAME")
    private String assetName;

    @TableField("CATEGORY_CODE")
    private String categoryCode;

    @TableField("DEPT_NAME")
    private String deptName;

    @TableField("COST_CENTER_CODE")
    private String costCenterCode;

    @TableField("ORIG_COST")
    private BigDecimal origCost;

    @TableField("ACCUM_DEPR")
    private BigDecimal accumDepr;

    @TableField("NBV")
    private BigDecimal nbv;

    /** 已提足标记（BR-32 校验锚） */
    @TableField("FULLY_DEPR_FLAG")
    private String fullyDeprFlag;

    /** 行处置价/评估价 */
    @TableField("LINE_PROCEEDS")
    private BigDecimal lineProceeds;
}
