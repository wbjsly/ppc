package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * NCR 操作日志（tasks 6.1~6.6，spec ncr-management）：
 * 评审 / 处置 / 关闭 / 超时升级（质量经理、质量总监）/ 超期提醒 / 解冻全链留痕，
 * 支撑 BR-4.12-25 分级时限与 BR-4.2-27 超 30 天升级的可追溯审计。
 */
@Getter
@Setter
@TableName("erp_qms_ncr_log")
public class NcrLog extends BaseEntity {

    @TableField("NCR_ID")
    private String ncrId;

    @TableField("NCR_NO")
    private String ncrNo;

    /** CREATE/REVIEW/DISPOSE/CONFIRM/CLOSE/CANCEL/ESCALATE_MGR/ESCALATE_DIRECTOR/REMIND/UNFREEZE/RECHECK */
    @TableField("ACTION")
    private String action;

    @TableField("FROM_STATUS")
    private String fromStatus;

    @TableField("TO_STATUS")
    private String toStatus;

    @TableField("DETAIL")
    private String detail;

    /** 升级接收角色 */
    @TableField("NOTIFY_ROLE")
    private String notifyRole;

    /** 抄送角色（采购经理） */
    @TableField("CC_ROLE")
    private String ccRole;

    @TableField("OPERATOR")
    private String operator;

    @TableField("OPERATOR_NAME")
    private String operatorName;

    // CREATE_DATE / CREATE_BY / DEL_FLAG / VER_NO 由 BaseEntity 提供（fill 自动填充）
}
