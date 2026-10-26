package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资产处置单（add-asset-disposal，design D1：DSP_NO 单号/两方式/状态机/评估/损益/应收）。
 */
@Getter
@Setter
@TableName("erp_eam_disposal_order")
public class EamDisposalOrder extends BaseEntity {

    /** 处置单号 DSP+yyyyMMdd+4位流水 */
    @TableField("DSP_NO")
    private String dspNo;

    /** SCRAP 报废 / SALE 出售 */
    @TableField("DISPOSAL_TYPE")
    private String disposalType;

    @TableField("REASON_CODE")
    private String reasonCode;

    @TableField("REASON_NOTE")
    private String reasonNote;

    /** DRAFT/PENDING_APPROVE/APPROVED/EXECUTED/RECEIVABLE/CLOSED/REJECTED */
    @TableField("APP_STATUS")
    private String appStatus;

    @TableField("APPR_ID")
    private String apprId;

    /** 评估残值 */
    @TableField("EST_RESIDUAL")
    private BigDecimal estResidual;

    @TableField("EVAL_REPORT_NO")
    private String evalReportNo;

    /** 残值偏高复核说明（C-4.13-05） */
    @TableField("REVIEW_NOTE")
    private String reviewNote;

    @TableField("TOTAL_COST")
    private BigDecimal totalCost;

    @TableField("TOTAL_ACCUM")
    private BigDecimal totalAccum;

    @TableField("TOTAL_NBV")
    private BigDecimal totalNbv;

    @TableField("TOTAL_PROCEEDS")
    private BigDecimal totalProceeds;

    @TableField("TOTAL_RECYCLE")
    private BigDecimal totalRecycle;

    /** 处置损益 = 处置价+回收-NBV */
    @TableField("GAIN_LOSS")
    private BigDecimal gainLoss;

    /** 应收处置款余额（BR-35 未结不得关闭） */
    @TableField("RECEIVABLE_BAL")
    private BigDecimal receivableBal;

    @TableField("EXECUTED_AT")
    private LocalDateTime executedAt;

    @TableField("REPORT_APPR_ID")
    private String reportApprId;

    @TableField("REPORT_SUMMARY")
    private String reportSummary;

    @TableField("REMARK")
    private String remark;
}
