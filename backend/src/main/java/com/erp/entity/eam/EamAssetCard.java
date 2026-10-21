package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 资产卡片（add-asset-capitalization，一物一卡）：
 * 折旧政策五字段（原值/年限/残值率/折旧方法/启用日）为 BR-4.13-10 锁定对象，
 * 亦是 7.2 折旧计提的数据源；LOCK_FLAG=1 后五字段不可改（走资产变更流程）。
 */
@Getter
@Setter
@TableName("erp_eam_asset_card")
public class EamAssetCard extends BaseEntity {

    /** ZC + yyyyMMdd + 4 位流水（系统生成不可手工修改） */
    @TableField("CARD_NO")
    private String cardNo;

    @TableField("ASSET_NAME")
    private String assetName;

    @TableField("SPEC_MODEL")
    private String specModel;

    @TableField("CATEGORY_CODE")
    private String categoryCode;

    @TableField("DEPT_NAME")
    private String deptName;

    @TableField("COST_CENTER_CODE")
    private String costCenterCode;

    @TableField("STORAGE_LOCATION")
    private String storageLocation;

    // ---- 折旧政策五字段（BR-4.13-10 锁定对象） ----

    @TableField("ASSET_COST")
    private BigDecimal assetCost;

    @TableField("LIFE_YEARS")
    private Integer lifeYears;

    @TableField("RESIDUAL_RATE")
    private BigDecimal residualRate;

    /** 残值额（原值×残值率，7.2 折旧缓存列，134 加列） */
    @TableField("RESIDUAL_VALUE")
    private BigDecimal residualValue;

    @TableField("DEPRECIATION_METHOD")
    private String depreciationMethod;

    @TableField("EFFECTIVE_DATE")
    private LocalDate effectiveDate;

    /** 起提月 yyyyMM（启用次月 BR-4.13-11，134 加列） */
    @TableField("DEPR_START_MONTH")
    private String deprStartMonth;

    /** IN_USE 在用 / DISPOSED 已处置（本变更仅建 IN_USE） */
    @TableField("STATUS")
    private String status;

    /** NOT_DEPRECIATED / DEPRECIATING / FULLY_DEPRECIATED（7.2 取用） */
    @TableField("DEPR_STATUS")
    private String deprStatus;

    @TableField("ACCUM_DEPR")
    private BigDecimal accumDepr;

    /** 减值准备（BR-4.13-15，134 加列）；NBV = 原值 − 累计折旧 − 减值准备 */
    @TableField("IMPAIRMENT")
    private BigDecimal impairment;

    /** 1=凭证过账后折旧政策锁定（BR-4.13-10） */
    @TableField("LOCK_FLAG")
    private String lockFlag;

    @TableField("SOURCE_CAP_ID")
    private String sourceCapId;

    @TableField("SOURCE_CAP_NO")
    private String sourceCapNo;

    @TableField("REMARK")
    private String remark;
}
