package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 资产分类主数据（add-asset-capitalization，spec asset-capitalization）：
 * 资本化下限（BR-4.13-09 L1 阻断依据）、默认折旧政策（FR-4.13-1-3 自动带入）、
 * 年限范围（L4 预警依据）、关联资产科目（转资凭证借方取数）。无审批立即生效。
 */
@Getter
@Setter
@TableName("erp_eam_asset_category")
public class EamAssetCategory extends BaseEntity {

    @TableField("CATEGORY_CODE")
    private String categoryCode;

    @TableField("NAME")
    private String name;

    /** 资本化金额下限（不含税，BR-4.13-09） */
    @TableField("CAPITALIZATION_FLOOR")
    private BigDecimal capitalizationFloor;

    /** 默认使用年限（年） */
    @TableField("DEFAULT_LIFE_YEARS")
    private Integer defaultLifeYears;

    /** 默认残值率（0.05 = 5%） */
    @TableField("DEFAULT_RESIDUAL_RATE")
    private BigDecimal defaultResidualRate;

    /** 默认折旧方法（本期仅 STRAIGHT_LINE） */
    @TableField("DEFAULT_DEPRECIATION_METHOD")
    private String defaultDepreciationMethod;

    /** 使用年限下限（L4 预警依据） */
    @TableField("LIFE_YEARS_MIN")
    private Integer lifeYearsMin;

    /** 使用年限上限（L4 预警依据） */
    @TableField("LIFE_YEARS_MAX")
    private Integer lifeYearsMax;

    /** 借方资产科目编码（erp_fin_account.ACCOUNT_CODE） */
    @TableField("ASSET_ACCOUNT")
    private String assetAccount;

    /** ACTIVE / INACTIVE */
    @TableField("STATUS")
    private String status;

    @TableField("REMARK")
    private String remark;
}
