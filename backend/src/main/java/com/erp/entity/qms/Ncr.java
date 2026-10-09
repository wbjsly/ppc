package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * NCR 不合格品报告（spec ncr-management）：判不合格同事务生成并冻结，
 * 两阶段冻结（未过账冻 GR 行 / 已入库冻库存，design D6）。
 * 评审分级时限 Critical 4h / Major 24h / Minor 48h，超 1 倍升质量经理、2 倍升质量总监。
 * PERF_FROZEN_FLAG：超 30 天未关闭冻结供应商本期绩效发布（BR-4.2-27，D4）。
 */
@Getter
@Setter
@TableName("erp_qms_ncr")
public class Ncr extends BaseEntity {
    /** NCR + yyyyMMdd + - + 6 位流水 */
    @TableField("NCR_NO")
    private String ncrNo;

    /** 关联检验批 */
    @TableField("LOT_ID")
    private String lotId;

    /** 检验批号 */
    @TableField("LOT_NO")
    private String lotNo;

    /** 关联收货单 */
    @TableField("GR_ID")
    private String grId;

    /** 关联收货行 */
    @TableField("GR_LINE_ID")
    private String grLineId;

    /** 关联 PO */
    @TableField("PO_ID")
    private String poId;

    /** PO 号 */
    @TableField("PO_NO")
    private String poNo;

    /** 供应商 */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 供应商名称 */
    @TableField("SUPPLIER_NAME")
    private String supplierName;

    /** 物料编码 */
    @TableField("ITEM_CODE")
    private String itemCode;

    /** 物料名称 */
    @TableField("ITEM_NAME")
    private String itemName;

    /** 批次 */
    @TableField("BATCH_NO")
    private String batchNo;

    /** 不合格数量 */
    @TableField("QTY")
    private java.math.BigDecimal qty;

    /** CRITICAL / MAJOR / MINOR */
    @TableField("SEVERITY")
    private String severity;

    /** 不合格项 */
    @TableField("DEFECT_ITEM")
    private String defectItem;

    /** 不合格描述 */
    @TableField("DEFECT_DESC")
    private String defectDesc;

    /** 1=CTQ 不合格 */
    @TableField("CTQ_FLAG")
    private String ctqFlag;

    /** 1=安全/法规（禁让步） */
    @TableField("REGULATORY_FLAG")
    private String regulatoryFlag;

    /** CREATED/REVIEWING/RETURNING/SORTING/REWORKING/CONCESSION/DISPOSED/CLOSED/CANCELLED */
    @TableField("STATUS")
    private String status;

    /** RETURN / SORT / REWORK / CONCESSION */
    @TableField("DISPOSITION")
    private String disposition;

    /** GR_LINE / STOCK / BOTH */
    @TableField("FREEZE_SCOPE")
    private String freezeScope;

    /** 1=冻结中 */
    @TableField("FROZEN_FLAG")
    private String frozenFlag;

    /** 评审时限 */
    @TableField("REVIEW_DUE_TIME")
    private LocalDateTime reviewDueTime;

    /** 评审超 1 倍时限：已升级质量经理（BR-4.12-25） */
    @TableField("REVIEW_ESCALATED_FLAG")
    private String reviewEscalatedFlag;

    /** 评审超时提醒次数 */
    @TableField("REVIEW_REMIND_COUNT")
    private Integer reviewRemindCount;

    /** 超 30 天后每 7 天提醒计数（BR-4.2-27） */
    @TableField("OVERDUE_REMIND_COUNT")
    private Integer overdueRemindCount;

    /** 上次升级/提醒时间（7 天周期基准） */
    @TableField("LAST_ESCALATE_TIME")
    private LocalDateTime lastEscalateTime;

    /** 挑选/返工处置方案（质量工程师确认，tasks 6.3） */
    @TableField("DISPOSE_PLAN")
    private String disposePlan;

    /** 处置执行结果凭证（tasks 6.4，缺失 422） */
    @TableField("DISPOSE_RESULT")
    private String disposeResult;

    /** 处置执行确认人 */
    @TableField("DISPOSE_BY")
    private String disposeBy;

    /** 评审人 */
    @TableField("REVIEW_BY")
    private String reviewBy;

    /** 评审时间 */
    @TableField("REVIEW_TIME")
    private LocalDateTime reviewTime;

    /** 评审意见 */
    @TableField("REVIEW_OPINION")
    private String reviewOpinion;

    /** 处置执行完成时间 */
    @TableField("DISPOSED_DATE")
    private LocalDateTime disposedDate;

    /** 关闭时间 */
    @TableField("CLOSED_DATE")
    private LocalDateTime closedDate;

    /** 关闭人 */
    @TableField("CLOSED_BY")
    private String closedBy;

    /** 1=超 30 天未关闭 */
    @TableField("OVERDUE_FLAG")
    private String overdueFlag;

    /** 1=已升级质量总监 */
    @TableField("ESCALATED_FLAG")
    private String escalatedFlag;

    /** 1=供应商绩效发布冻结 */
    @TableField("PERF_FROZEN_FLAG")
    private String perfFrozenFlag;

    /** 关联 CAPA */
    @TableField("CAPA_ID")
    private String capaId;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
