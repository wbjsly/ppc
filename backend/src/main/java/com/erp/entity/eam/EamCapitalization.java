package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 转资单（add-asset-capitalization，spec asset-capitalization FR-4.13-1-2~6）：
 * 双模式 PURCHASE/CIP；一物一卡拆分（D3 上限 500）；
 * 状态机 DRAFT → PENDING_APPROVE →（REJECTED 回草稿）→ COMPLETED。
 */
@Getter
@Setter
@TableName("erp_eam_capitalization")
public class EamCapitalization extends BaseEntity {

    /** ZT + yyyyMMdd + - + 4 位流水 */
    @TableField("CAP_NO")
    private String capNo;

    /** PURCHASE 采购转资 / CIP 在建转资 */
    @TableField("MODE")
    private String mode;

    /** 来源单据 ID（采购订单 ID / CIP 项目 ID） */
    @TableField("SOURCE_ID")
    private String sourceId;

    @TableField("SOURCE_DOC_NO")
    private String sourceDocNo;

    @TableField("ASSET_NAME")
    private String assetName;

    @TableField("SPEC_MODEL")
    private String specModel;

    /** 一物一卡拆分数量（上限 500，D3） */
    @TableField("QTY")
    private Integer qty;

    @TableField("CATEGORY_CODE")
    private String categoryCode;

    @TableField("DEPT_NAME")
    private String deptName;

    @TableField("COST_CENTER_CODE")
    private String costCenterCode;

    @TableField("STORAGE_LOCATION")
    private String storageLocation;

    /** 原值合计（单件原值 × 数量，BR-4.13-07 校验对象） */
    @TableField("TOTAL_AMOUNT")
    private BigDecimal totalAmount;

    /** 单件原值（建卡逐件取值） */
    @TableField("UNIT_AMOUNT")
    private BigDecimal unitAmount;

    @TableField("LIFE_YEARS")
    private Integer lifeYears;

    @TableField("RESIDUAL_RATE")
    private BigDecimal residualRate;

    @TableField("DEPRECIATION_METHOD")
    private String depreciationMethod;

    /** 启用日期（BR-4.13-06 = 验收合格日） */
    @TableField("EFFECTIVE_DATE")
    private LocalDate effectiveDate;

    /** 1=部分转资（BR-4.13-08 强制标志） */
    @TableField("PARTIAL_FLAG")
    private String partialFlag;

    @TableField("PARTIAL_REMARK")
    private String partialRemark;

    /** 调整原因（FR-4.13-1-3 改默认政策需说明） */
    @TableField("ADJUST_REASON")
    private String adjustReason;

    /** DRAFT / PENDING_APPROVE / APPROVED / REJECTED / COMPLETED */
    @TableField("STATUS")
    private String status;

    @TableField("APPROVAL_ID")
    private String approvalId;

    @TableField("REJECT_REASON")
    private String rejectReason;

    /** 转资凭证 ID（业财锚点） */
    @TableField("VOUCHER_ID")
    private String voucherId;

    /** 建卡数量（审核通过后回写） */
    @TableField("CARD_COUNT")
    private Integer cardCount;

    @TableField("REMARK")
    private String remark;
}
