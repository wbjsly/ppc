package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 资产变动单（add-asset-depreciation，design D4）：
 * POLICY（政策变更快照+下期生效）/ IMPAIRMENT（减值凭证+次月重算）/ REVERSE（折旧整单冲回）三型共用。
 */
@Getter
@Setter
@TableName("erp_eam_asset_change")
public class EamAssetChange extends BaseEntity {

    /** BD + yyyyMMdd + - + 4 位流水 */
    @TableField("CHANGE_NO")
    private String changeNo;

    /** POLICY / IMPAIRMENT / REVERSE */
    @TableField("CHANGE_TYPE")
    private String changeType;

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    // ---- 政策变更快照（BR-4.13-14：before/after，下期生效不追溯）----

    @TableField("BEFORE_LIFE_YEARS")
    private Integer beforeLifeYears;

    @TableField("AFTER_LIFE_YEARS")
    private Integer afterLifeYears;

    @TableField("BEFORE_RESIDUAL_RATE")
    private BigDecimal beforeResidualRate;

    @TableField("AFTER_RESIDUAL_RATE")
    private BigDecimal afterResidualRate;

    @TableField("BEFORE_METHOD")
    private String beforeMethod;

    @TableField("AFTER_METHOD")
    private String afterMethod;

    /** 政策变更生效期间（下期 yyyyMM） */
    @TableField("EFFECTIVE_PERIOD")
    private String effectivePeriod;

    // ---- 减值（BR-4.13-15）----

    /** 减值金额 */
    @TableField("IMPAIR_AMOUNT")
    private BigDecimal impairAmount;

    /** 减值后账面净值（次月重算基数） */
    @TableField("IMPAIR_AFTER_NBV")
    private BigDecimal impairAfterNbv;

    /** 减值凭证 */
    @TableField("IMPAIR_VOUCHER_ID")
    private String impairVoucherId;

    // ---- 冲回（REVERSE：原计提单 + 红冲凭证）----

    @TableField("RUN_ID")
    private String runId;

    @TableField("REVERSE_VOUCHER_ID")
    private String reverseVoucherId;

    // ---- 审批底座 ----

    @TableField("APPR_ID")
    private String apprId;

    /** DRAFT / PENDING_APPROVE / APPROVED / REJECTED */
    @TableField("STATUS")
    private String status;

    @TableField("REASON")
    private String reason;

    @TableField("REMARK")
    private String remark;
}
