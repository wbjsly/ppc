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

    /** 所属法人 LE_CODE（137 加列，存量回填默认法人；跨法人调拨即改） */
    @TableField("LEGAL_ENTITY")
    private String legalEntity;

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

    /** 最后使用日（137 加列，闲置判定锚 BR-4.13-05，回填=启用日期） */
    @TableField("LAST_USED_AT")
    private LocalDate lastUsedAt;

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

    /** 质押抵押 1=是（BR-4.13-21 调拨阻断，137 加列） */
    @TableField("PLEDGED_FLAG")
    private String pledgedFlag;

    /** 盘点冻结 1=标签异常/账实冲突冻结调拨处置（BR-4.13-30，138 加列） */
    @TableField("COUNT_FREEZE_FLAG")
    private String countFreezeFlag;

    /** 租赁资产（ROU）1=是（137 加列） */
    @TableField("LEASE_FLAG")
    private String leaseFlag;

    /** 租赁合同锚（ROU 卡，137 加列） */
    @TableField("LEASE_CONTRACT_ID")
    private String leaseContractId;

    @TableField("SOURCE_CAP_ID")
    private String sourceCapId;

    @TableField("SOURCE_CAP_NO")
    private String sourceCapNo;

    /** 处置执行确认日（BR-34 当月豁免/次月停提锚，139 加列；IGNORED=冲回时可置 null 清空） */
    @TableField(value = "DISPOSED_AT", updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.IGNORED)
    private java.time.LocalDateTime disposedAt;

    @TableField("REMARK")
    private String remark;
}
