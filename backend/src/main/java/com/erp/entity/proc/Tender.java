package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 招标项目（2.2.2，表 erp_proc_tender，FR-4.2-10-1/2/3）。
 * 状态机见 TenderStateMachine；报价截止懒 sweep 锁价。独立于 erp_proc_rfq（design D1）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_proc_tender")
public class Tender extends BaseEntity {

    /** TND-YYYYMMDD-NNN 按日流水，全局唯一 */
    private String tenderNo;

    private String title;

    /** OPEN 公开招标 / INVITE 邀请招标 */
    private String tenderType;

    /** LOWEST 最低价法 / SCORE 综合评分法（FR-4.2-10-1 评标办法） */
    private String evalMethod;

    /** 招标品类（BR-4.2-44 门槛统计维度） */
    private String categoryCode;
    private String categoryName;

    /** 年度预估量 */
    private BigDecimal estAnnualQty;

    /** 技术规格书（附件桩：文本说明） */
    private String techSpec;

    /** 投标资格门槛 */
    private String qualifyReq;

    /** DRAFT / BIDDING / LOCKED / EVALUATING / PENDING_AWARD / AWAITING_PUBLICITY / OBJECTION / AWARDED / CANCELLED */
    private String status;

    /** 报名截止（BR-4.2-45 延期报名期） */
    private LocalDateTime regDeadline;

    /** 报价截止（单一总截止，锁价时点） */
    private LocalDateTime quoteDeadline;

    /** 转邀请招标审批：0 无需 / 1 待采购总监审批 / 2 已批准 / 3 已驳回（BR-4.2-45） */
    private String invitePending;

    /** 评分权重快照（和=100，可按品类调整，design D4） */
    private Integer weightPrice;
    private Integer weightDelivery;
    private Integer weightQuality;
    private Integer weightCooperation;

    // ---- 定标快照 ----
    private String awardSupplierId;
    private BigDecimal awardPrice;
    private BigDecimal awardScore;

    // ---- 定标审批（design D6：招标独立单节点，采购总监） ----
    /** NONE 未提交 / PENDING 待审批 / APPROVED 已通过 / REJECTED 已驳回 */
    private String awardApprovalStatus;
    private String awardApprovedBy;
    private LocalDateTime awardApprovedDate;
    private String awardApprovalNote;

    // ---- 公示（BR-4.2-48） ----
    private LocalDateTime publicityStart;
    private LocalDateTime publicityEnd;
    /** 有效异议标记（暂停协议生成） */
    private String objectionFlag;

    /** 围标/串标异常标记（偏差 D6：人工标记，冻结评标） */
    private String anomalyFlag;
    private String anomalyNote;

    /** 合格投标方数（BR-4.2-45 判定） */
    private Integer qualifiedCount;

    private String abortReason;
    private String closeReason;
}
