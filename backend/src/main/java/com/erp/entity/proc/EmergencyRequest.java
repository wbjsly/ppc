package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 紧急采购申请单（2.1.3，表 erp_proc_emergency，S-4.2-04 + BR-4.2-11 + L1321）。
 * 特批留痕直接落列（单节点，不复用审批表，design D2）；状态机见 EmergencyStateMachine。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_proc_emergency")
public class EmergencyRequest extends BaseEntity {

    /** EA-YYYYMMDD-NNN 按日流水，全局唯一 */
    private String eaNo;

    private String prId;

    /** STOP_LINE / CUSTOMER_RUSH / OTHER */
    private String reasonType;

    private String reasonDesc;

    private LocalDate expectArriveDate;

    /** PENDING_SPECIAL_APPROVAL / APPROVED_EMERGENCY / FILLING / COMPLETED / OVERDUE_EXCEPTION / REJECTED / CLOSED */
    private String status;

    /** 申请人账号（冗余列，阻断/例外聚合查询） */
    private String applicant;

    // ---- 特批留痕（采购总监单节点） ----
    private String specialApproveBy;
    private LocalDateTime specialApproveDate;
    private String specialReason;
    private String rejectReason;

    /** 放行：补齐截止日 = 特批日 + EMERGENCY_FILL_DAYS（跳周末） */
    private LocalDate clearanceDueDate;

    // ---- 比价补齐（文本登记，附件桩） ----
    private Integer quoteCount;
    private String compareDocNo;
    private String fillNote;
    private LocalDateTime fillDate;

    // ---- 标记 ----
    private String remindFlag;
    private String escalateFlag;
    private String exceptionFlag;
    private LocalDateTime scanDate;
    private String closeReason;
}
