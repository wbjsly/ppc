package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * PO 审批节点任务（change add-framework-agreement-order，design D1：独立实现，不动 2.1.4）。
 * 三档判级（FR-4.2-3-2）：常规→采购经理；超预算→+采购总监；特殊→采购总监→分管副总裁；
 * 价控命中强制链尾追加采购总监（ESCALATE_FLAG）。
 * 状态：ACTIVE 当前节点 / APPROVED / REJECTED / SUPERSEDED 被驳回连带作废。
 */
@Data
@TableName("erp_proc_po_approval_task")
public class PoApprovalTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String poId;

    /** 提交批次，驳回重提 +1，历史保留 */
    private Integer submitBatch;

    private Integer nodeNo;

    /** PURCHASE_MANAGER / PURCHASE_DIRECTOR / VICE_PRESIDENT */
    private String nodeRole;

    private String nodeLabel;

    /** ACTIVE / APPROVED / REJECTED / SUPERSEDED */
    private String status;

    /** PASS / REJECT / CONDITIONAL */
    private String action;

    private String actionReason;

    /** 条件批准的附加条件 */
    private String conditionText;

    private String actedBy;

    private String actedByName;

    private LocalDateTime actionDate;

    /** 价控/预算命中的强制加签标记 */
    private String escalateFlag;

    private String escalateReason;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;

    private Integer verNo;
}
