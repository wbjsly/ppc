package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 试产总结单（change add-trial-production-management，spec trial-execution
 * FR-4.5-8-6；迁移 125）。与试产工单 1:1（UK MO_ID）。
 * 良率/工时达成率服务端计算禁手填；评审 = TrialReview 三方并行会签（工艺/质量/计划）；
 * APPROVED 是阶段闸口与转量产解锁依据。
 */
@Getter
@Setter
@TableName("erp_mrp_trial_summary")
public class TrialSummary extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    /** 总结单号 TS-YYYYMMDD-NNN（冲突自动递增） */
    private String summaryNo;
    /** 试产工单 ID（1:1） */
    private String moId;
    /** 试产工单号 TP-（冗余查询） */
    private String moNo;
    /** 试产阶段（快照自工单） */
    private String trialStage;
    /** 良率% = 合格产出 ÷ 报工投入 × 100（服务端计算禁手填，5.7 同口径 goodQty/inputQty） */
    private BigDecimal yieldPct;
    /** 工时达成率% = 标准工时Σ ÷ 实测工时Σ × 100（服务端计算） */
    private BigDecimal hoursAchievePct;
    /** 合格产出（计算分项留痕） */
    private BigDecimal qualifiedQty;
    /** 报工投入（计算分项留痕） */
    private BigDecimal inputQty;
    /** 标准工时合计（工序快照） */
    private BigDecimal stdHours;
    /** 实测工时合计（采集表） */
    private BigDecimal actualHours;
    /** 问题清单（多行，每行 现象/建议） */
    private String issues;
    /** 评审状态机（白名单 CAS 迁移） */
    private String status;
    /** TrialReview 审批实例 ID */
    private String approvalId;
    /** 最近驳回意见 */
    private String rejectReason;

    private String submitBy;
    private LocalDateTime submitAt;
    private String approveBy;
    private LocalDateTime approveAt;
}
