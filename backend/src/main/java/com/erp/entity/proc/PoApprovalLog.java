package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * PO 审批日志（批次/节点/动作/原因/人/时间，倒序可查；spec purchase-order「审批动作与待办」）。
 */
@Data
@TableName("erp_proc_po_approval_log")
public class PoApprovalLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String poId;

    private String poNo;

    private Integer submitBatch;

    private Integer nodeNo;

    private String nodeRole;

    /** SUBMIT / PASS / REJECT / CONDITIONAL / ESCALATE */
    private String action;

    private String actionReason;

    private String actedBy;

    private String actedByName;

    private LocalDateTime actionDate;
}
