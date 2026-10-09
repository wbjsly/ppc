package com.erp.entity.system;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 审批节点任务：同一 SEQ 下多节点 = 并行（双签/会签），全部 PASSED 才推进下一 SEQ；
 * 任一 REJECTED 整单驳回（C-4.12-01 双签缺一不可）。
 * 超时：REMIND_COUNT（72h 提醒）/ ESCALATED（7 天升级）。
 */
@Getter
@Setter
@TableName("erp_sys_approval_task")
public class ApprovalTask extends BaseEntity {
    /** 审批实例 ID */
    @TableField("APPR_ID")
    private String apprId;

    /** 节点序号；同 SEQ 多节点并行 */
    @TableField("SEQ")
    private Integer seq;

    /** SIGN 签署 / JOINT 会签 */
    @TableField("NODE_TYPE")
    private String nodeType;

    /** 所需角色（ROLE_ 形式） */
    @TableField("ROLE_REQUIRED")
    private String roleRequired;

    /** 节点说明 */
    @TableField("NODE_NAME")
    private String nodeName;

    /** 签署人 ID */
    @TableField("SIGNER")
    private String signer;

    /** 签署人姓名 */
    @TableField("SIGNER_NAME")
    private String signerName;

    /** ACTIVE / PASSED / REJECTED / SKIPPED */
    @TableField("STATUS")
    private String status;

    /** 审批意见（驳回必填 ≥2 字） */
    @TableField("OPINION")
    private String opinion;

    /** 签署时间 */
    @TableField("OP_TIME")
    private LocalDateTime opTime;

    /** 超时提醒计数 */
    @TableField("REMIND_COUNT")
    private Integer remindCount;

    /** 1=已升级 */
    @TableField("ESCALATED")
    private String escalated;

    /** 升级待办关联 */
    @TableField("ESCALATE_TODO_ID")
    private String escalateTodoId;

}
