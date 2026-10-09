package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购订单行（spec purchase-order）。
 * 协议来源行单价锁定（L908 不可修改）；RECEIVED_QTY 为 2.4 收货预留占位列。
 * 表无 DEL_FLAG，不继承 BaseEntity（同 TenderLine 惯例）。
 */
@Data
@TableName("erp_proc_po_line")
public class PurchaseOrderLine implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String poId;

    private Integer lineNo;

    private String itemCode;

    private String itemName;

    private BigDecimal qty;

    private String unit;

    /** 协议来源 = 协议行单价带出，只读（FR-4.2-3-1） */
    private BigDecimal unitPrice;

    private BigDecimal taxRate;

    /** 行金额（未税口径 = qty × unitPrice，展示含税另算） */
    private BigDecimal amount;

    private LocalDate reqDate;

    /** 协议行溯源（AGREEMENT 来源必填） */
    private String agreementLineId;

    /** PR 行溯源（RFQ/手工带 PR 时回写下达量，BR-4.2-51） */
    private String prLineId;

    /** 已收货数量（2.4 过账回写，change add-goods-receipt） */
    private BigDecimal receivedQty;

    /** 行收货状态：OPEN 未完成 / COMPLETED 已收满（receivedQty ≥ qty，037 回填） */
    private String lineStatus;

    /** 价控结果：PASS / ESCALATE / BLOCK / NO_HISTORY / NO_BUDGET（组 7 回填） */
    private String priceCtrlResult;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;

    private Integer verNo;
}
