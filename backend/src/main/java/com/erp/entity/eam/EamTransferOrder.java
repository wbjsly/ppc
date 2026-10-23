package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资产调拨单（add-asset-transfer-lease，design D1）：
 * DEPT 部门间 / CROSS_LE 跨法人一张表两模式；FROM/TO 三组快照申请时落单，
 * 完成时 DEPT 即改卡片、CROSS_LE 走内部交易双凭证（BR-4.13-23/C-4.13-10）。
 */
@Getter
@Setter
@TableName("erp_eam_transfer_order")
public class EamTransferOrder extends BaseEntity {

    /** TRF + yyyyMMdd + - + 4 位流水 */
    @TableField("TRF_NO")
    private String trfNo;

    /** DEPT / CROSS_LE */
    @TableField("TRANSFER_TYPE")
    private String transferType;

    @TableField("CARD_ID")
    private String cardId;

    @TableField("CARD_NO")
    private String cardNo;

    // ---- FROM/TO 三组快照 ----

    @TableField("FROM_DEPT")
    private String fromDept;

    @TableField("FROM_CC")
    private String fromCc;

    @TableField("FROM_LE")
    private String fromLe;

    @TableField("TO_DEPT")
    private String toDept;

    @TableField("TO_CC")
    private String toCc;

    @TableField("TO_LE")
    private String toLe;

    @TableField("FROM_LOCATION")
    private String fromLocation;

    @TableField("TO_LOCATION")
    private String toLocation;

    @TableField("REASON")
    private String reason;

    @TableField("PLAN_DATE")
    private LocalDate planDate;

    /** DRAFT/PENDING_APPROVE/APPROVED/HANDOVERED/COMPLETED/REJECTED/CANCELLED */
    @TableField("STATUS")
    private String status;

    @TableField("APPR_ID")
    private String apprId;

    @TableField("REJECT_REASON")
    private String rejectReason;

    // ---- 交接双确认 ----

    @TableField("OUT_CONFIRM_BY")
    private String outConfirmBy;

    @TableField("OUT_CONFIRM_AT")
    private LocalDateTime outConfirmAt;

    @TableField("IN_CONFIRM_BY")
    private String inConfirmBy;

    @TableField("IN_CONFIRM_AT")
    private LocalDateTime inConfirmAt;

    /** 完成时间（BR-22 折旧回溯锚：完成当月折旧仍归调出方） */
    @TableField("COMPLETED_AT")
    private LocalDateTime completedAt;

    // ---- 跨法人内部交易 ----

    /** ICR + yyyyMMdd + - + 4 位流水 */
    @TableField("INTERCO_NO")
    private String intercoNo;

    @TableField("OUT_VOUCHER_ID")
    private String outVoucherId;

    @TableField("IN_VOUCHER_ID")
    private String inVoucherId;

    @TableField("NEW_CARD_ID")
    private String newCardId;

    /** 调入方新卡 NBV（原值；内部交易单据留痕） */
    @TableField("NBV_AMOUNT")
    private BigDecimal nbvAmount;

    @TableField("REMARK")
    private String remark;
}
