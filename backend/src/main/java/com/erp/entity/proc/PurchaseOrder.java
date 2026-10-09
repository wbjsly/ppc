package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购订单头（change add-framework-agreement-order，2.3.1，spec purchase-order）。
 * 表 erp_proc_po；编号 PO + yyyyMM + 6 位流水；状态 DRAFT→APPROVING→APPROVED→CLOSED。
 * 版本：CURR_VERSION 递增，行级版本快照入 erp_proc_po_version（旧版本只读）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_proc_po")
public class PurchaseOrder extends BaseEntity {

    /** PO + yyyyMM + 6 位流水（唯一索引） */
    private String poNo;

    /** NORMAL 常规 / SPECIAL 特殊（紧急、关联交易 → 审批走高档，FR-4.2-3-2） */
    private String poType;

    /** AGREEMENT 协议 / RFQ 中选 / MANUAL 手工 */
    private String source;
    /** EDI 第四入口来源报文编号（spec purchase-order ADDED） */
    private String ediMsgNo;
    private String ediIdemKey;

    /** 来源单据 ID（协议 ID / RFQ ID；手工为 null） */
    private String sourceId;

    private String supplierId;

    private String supplierName;

    /** 关联 PR（RFQ/手工来源可空） */
    private String prId;

    private String prNo;

    /** DRAFT 草稿 / APPROVING 待审批 / APPROVED 已批准（已下达）/ CLOSED 已关闭 */
    private String status;

    /** 当前审批批次（驳回重提 +1，历史保留） */
    private Integer approvalBatch;

    /** 含税总金额 */
    private BigDecimal totalAmt;

    /** 供应商确认的承诺交期（spec po-collaboration） */
    private java.time.LocalDate promiseDate;
    /** PUSHED/CONFIRMED/CHANGE_PENDING/ESCATED（NULL=未推送） */
    private String confirmStatus;
    /** 供应商确认入口锁定（连续 3 张超时，design D5）：0/1 */
    private String coopLock;

    /** 约定预付比例（C-4.2-13，047 增列；空则按 app.proc.prepay-ratio 默认） */
    private BigDecimal prepayRatio;

    private BigDecimal taxRate;

    private String taxCode;

    private String currency;

    private String remark;

    private String closeReason;

    private String closedBy;

    private LocalDateTime closedDate;

    /** 当前版本号（变更递增；旧版本只读，FR-4.2-9/BR-4.2-03） */
    private Integer currVersion;
}
