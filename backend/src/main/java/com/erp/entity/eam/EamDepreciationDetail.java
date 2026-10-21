package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 折旧明细（一行一卡，add-asset-depreciation，design D1）：
 * 明细↔凭证双向反查（VOUCHER_ID + RUN 锚点）；含成本分摊借方科目与提足/预警/冲回标记。
 */
@Getter
@Setter
@TableName("erp_eam_depreciation_detail")
public class EamDepreciationDetail extends BaseEntity {

    @TableField("RUN_ID")
    private String runId;

    @TableField("PERIOD_TAG")
    private String periodTag;

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    /** 卡片名称快照 */
    @TableField("ASSET_NAME")
    private String assetName;

    /** 卡片原值快照 */
    @TableField("ASSET_COST")
    private BigDecimal assetCost;

    /** 折旧方法快照 */
    @TableField("DEPRECIATION_METHOD")
    private String depreciationMethod;

    /** 期初累计折旧 */
    @TableField("BEGIN_ACCUM_DEPR")
    private BigDecimal beginAccumDepr;

    /** 期初账面净值 */
    @TableField("BEGIN_NBV")
    private BigDecimal beginNbv;

    /** 本期折旧 */
    @TableField("PERIOD_DEPR")
    private BigDecimal periodDepr;

    /** 期末累计折旧 */
    @TableField("END_ACCUM_DEPR")
    private BigDecimal endAccumDepr;

    /** 期末账面净值 */
    @TableField("END_NBV")
    private BigDecimal endNbv;

    @TableField("COST_CENTER_CODE")
    private String costCenterCode;

    /** 借方费用科目（成本分摊 FR-4.6-5-4） */
    @TableField("DEBIT_ACCOUNT")
    private String debitAccount;

    /** 1=残值占NBV超阈值（C-4.6-04 L4） */
    @TableField("RESIDUAL_ALERT")
    private String residualAlert;

    /** 1=本期提足截减（BR-4.13-12） */
    @TableField("CAPPED")
    private String capped;

    /** 1=已冲回（整单全冲） */
    @TableField("REVERSED")
    private String reversed;

    @TableField("VOUCHER_ID")
    private String voucherId;

    @TableField("REMARK")
    private String remark;
}
