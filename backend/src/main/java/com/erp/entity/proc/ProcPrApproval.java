package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审批任务与审批日志（erp_proc_pr_approval，FR-4.2-1-2 + BR-4.2-10）。
 * 一行 = 一个节点任务（含其动作记录）；重提批次 +1 保留历史（design D7）。
 */
@Data
@TableName("erp_proc_pr_approval")
public class ProcPrApproval implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String prId;

    /** 重提批次（驳回重提 +1） */
    private Integer submitBatch;

    private Integer nodeNo;

    /** DEPT_MANAGER / DIRECTOR / VP */
    private String nodeRole;

    /** ACTIVE / PASSED / REJECTED / SUPERSEDED */
    private String status;

    /** SUBMIT / PASS / REJECT / ESCALATE */
    private String action;

    private String actionReason;

    private String actedBy;

    private LocalDateTime actionDate;

    private String timeoutFlag;

    private String escalateFlag;

    /** 升级对象（上一级节点角色） */
    private String escalateTo;

    private LocalDateTime scanDate;

    private String createBy;

    private LocalDateTime createDate;
}
