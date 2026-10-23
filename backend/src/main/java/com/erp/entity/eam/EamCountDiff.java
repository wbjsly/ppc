package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 盘点差异单（add-asset-count，design D1/D2/D5，C-4.13-04 L5 全留痕）：
 * 四类差异（盘盈 OVER/盘亏 SHORT/标签异常 TAG_ISSUE/账实冲突 STATUS_CONFLICT）逐单分级审批；
 * 盘亏两段式：挂账（1901）→ 责任认定 → 核销（BR-28）；盘盈自动带价建卡（BR-27）。
 */
@Getter
@Setter
@TableName("erp_eam_count_diff")
public class EamCountDiff extends BaseEntity {

    @TableField("PLAN_ID")
    private String planId;

    @TableField("CNT_NO")
    private String cntNo;

    @TableField("LINE_ID")
    private String lineId;

    /** OVER/SHORT/TAG_ISSUE/STATUS_CONFLICT */
    @TableField("DIFF_TYPE")
    private String diffType;

    // ---- 卡片与快照回显 ----

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    @TableField("SNAP_NAME")
    private String snapName;

    @TableField("SNAP_DEPT")
    private String snapDept;

    @TableField("SNAP_CC")
    private String snapCc;

    @TableField("SNAP_CATEGORY")
    private String snapCategory;

    @TableField("SNAP_COST")
    private BigDecimal snapCost;

    @TableField("SNAP_ACCUM")
    private BigDecimal snapAccum;

    @TableField("SNAP_NBV")
    private BigDecimal snapNbv;

    /** 差异原因（C-4.13-04 调查留痕） */
    @TableField("REASON")
    private String reason;

    /** 证据（报废审批单/调拨单等） */
    @TableField("EVIDENCE")
    private String evidence;

    // ---- 差异金额与盘盈计价（BR-27） ----

    /** 盘亏=NBV / 盘盈=入账价值 */
    @TableField("DIFF_AMOUNT")
    private BigDecimal diffAmount;

    /** 自动带价：同分类 12 月转资原值均价 */
    @TableField("SUGGESTED_PRICE")
    private BigDecimal suggestedPrice;

    @TableField("PRICE_BASIS")
    private String priceBasis;

    // ---- 审批锚 ----

    @TableField("APPR_ID")
    private String apprId;

    // ---- 责任认定（BR-28） ----

    /** NONE 无人为 / COMPENSATE 责任人赔偿 / INSURANCE 保险理赔（桩位） */
    @TableField("LIABILITY_TYPE")
    private String liabilityType;

    @TableField("LIABILITY_PERSON")
    private String liabilityPerson;

    @TableField("COMPENSATE_AMOUNT")
    private BigDecimal compensateAmount;

    @TableField("LIABILITY_BASIS")
    private String liabilityBasis;

    // ---- 账务锚（D5） ----

    /** NONE/SUSPENDED 挂账/POSTED 核销完成 */
    @TableField("ADJUST_STATUS")
    private String adjustStatus;

    @TableField("VOUCHER_ID")
    private String voucherId;

    /** 1=已冻结卡片调拨处置（BR-30） */
    @TableField("FROZEN_FLAG")
    private String frozenFlag;

    /** PENDING→APPROVED→LIABILITY_PENDING→READY_TO_POST→POSTED→CLOSED */
    @TableField("STATUS")
    private String status;

    @TableField("REJECT_REASON")
    private String rejectReason;

    @TableField("REMARK")
    private String remark;
}
