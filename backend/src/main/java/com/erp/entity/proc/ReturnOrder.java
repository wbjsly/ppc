package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 退货单（2.6.1 质量退货，spec quality-return）：
 * NCR 退货处置自动带出（BR-4.2-32 例外口，未过账按 PO 单价）；
 * 状态机 DRAFT → PENDING_APPROVE → APPROVED | REJECTED → OUT_DONE；
 * 出库生成红字入库凭证（RV）+ 30 天补发/退款跟踪（BR-4.2-34）。
 * PERF_FLAG：质量退货绩效扣分标记（D4 绩效模块未建，标记位留痕）。
 */
@Getter
@Setter
@TableName("erp_proc_return")
public class ReturnOrder extends BaseEntity {
    /** RT + yyyyMMdd + - + 6 位流水 */
    @TableField("RETURN_NO")
    private String returnNo;

    /** NCR / MANUAL */
    @TableField("SOURCE_TYPE")
    private String sourceType;

    /** 来源 NCR */
    @TableField("NCR_ID")
    private String ncrId;

    /** NCR 号 */
    @TableField("NCR_NO")
    private String ncrNo;

    /** 关联 PO */
    @TableField("PO_ID")
    private String poId;

    /** PO 号 */
    @TableField("PO_NO")
    private String poNo;

    /** 关联收货单 */
    @TableField("GR_ID")
    private String grId;

    /** 收货单号 */
    @TableField("GR_NO")
    private String grNo;

    /** 原入库单号（已入库必填） */
    @TableField("ORIGIN_DOC_NO")
    private String originDocNo;

    /** 供应商 */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 供应商名称 */
    @TableField("SUPPLIER_NAME")
    private String supplierName;

    /** 退货总数量 */
    @TableField("TOTAL_QTY")
    private java.math.BigDecimal totalQty;

    /** 退货总金额 */
    @TableField("TOTAL_AMT")
    private java.math.BigDecimal totalAmt;

    /** DRAFT/PENDING_APPROVE/APPROVED/REJECTED/OUT_DONE/CANCELLED */
    @TableField("STATUS")
    private String status;

    /** 采购经理审批实例 */
    @TableField("APPROVAL_ID")
    private String approvalId;

    /** 驳回原因 */
    @TableField("REJECT_REASON")
    private String rejectReason;

    /** 出库执行人 */
    @TableField("OUT_BY")
    private String outBy;

    /** 出库时间 */
    @TableField("OUT_DATE")
    private LocalDateTime outDate;

    /** 红字入库凭证号（RV） */
    @TableField("RED_DOC_NO")
    private String redDocNo;

    /** 30 天跟踪到期日 */
    @TableField("TRACK_DUE_DATE")
    private LocalDate trackDueDate;

    /** OPEN / CLOSED / OVERDUE */
    @TableField("TRACK_STATUS")
    private String trackStatus;

    /** 跟踪闭环时间 */
    @TableField("TRACK_CLOSED_DATE")
    private LocalDateTime trackClosedDate;

    /** 1=质量退货绩效扣分标记 */
    @TableField("PERF_FLAG")
    private String perfFlag;

    /** 退货原因 */
    @TableField("RETURN_REASON")
    private String returnReason;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
