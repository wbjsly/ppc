package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 请购单头（采购管理 2.1.1/2.1.2/2.1.4，表 erp_proc_requisition，FR-4.2-1-1）。
 * 状态机见 design D2；单据域：字段留痕 + 操作日志，非主数据版本快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_proc_requisition")
public class ProcRequisition extends BaseEntity {

    /** PR-YYYYMMDD-NNN 按日流水，全局唯一 */
    private String prNo;

    /** MRP / MANUAL */
    private String sourceType;

    /** design D2 状态机（PENDING_CONFIRM/PENDING_BUDGET/PENDING_APPROVAL/PENDING_MODIFY/CONFIRMED/APPROVING/APPROVED/PENDING_RFQ/CLOSED） */
    private String status;

    // ---- 需求来源三选一（手工） ----
    private String reqProjectNo;
    private String reqCostCenterId;
    private String reqInternalOrderNo;

    // ---- 预算来源三选一（BR-4.2-09） ----
    private String budgetSubject;
    private String budgetCostCenterId;
    private String budgetInternalOrderNo;

    private String reqReason;
    private String rejectReason;

    // ---- 确认/关闭留痕 ----
    private String confirmBy;
    private LocalDateTime confirmDate;
    private String closeReason;
    private String closeBy;
    private LocalDateTime closeDate;

    // ---- 催办/升级（BR-4.2-50 懒 sweep） ----
    private String remindFlag;
    private String escalateFlag;
    private LocalDateTime scanDate;

    /** 判级金额快照 Σ(qty×estUnitPrice) */
    private BigDecimal approvalAmount;
}
