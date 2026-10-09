package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收货调整单（BR-4.2-49，P-1 独立轻量链，change add-goods-receipt design D4）。
 * 状态机：DRAFT → PENDING_APPROVE → APPROVED | REJECTED → EXECUTED。
 * 执行只加 PO 行数量（价格列有意不设——价格零变化，不进变更链不重跑价控）。
 */
@Getter
@Setter
@TableName("erp_proc_gr_adjustment")
public class ReceiptAdjustment extends BaseEntity {

    /** GRADJ-YYYYMMDD-NNN */
    private String adjNo;

    /** 关联差异单（迟到货物场景可空） */
    private String diffId;

    private String poId;
    private String poNo;
    private String poLineId;
    private String itemCode;

    /** 追加数量（仅数量方向） */
    private BigDecimal addQty;
    private BigDecimal oldQty;
    private BigDecimal newQty;

    /** DRAFT / PENDING_APPROVE / APPROVED / REJECTED / EXECUTED */
    private String status;

    private String initiator;
    private String initiatorName;
    private String approver;
    private String approverName;
    private String approveNote;
    private LocalDateTime executeDate;
    /** 执行时生成的关联原 PO 新收货单 */
    private String newGrId;
}
