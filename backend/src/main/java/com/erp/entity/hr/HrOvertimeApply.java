package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 加班申请（FR-4.7-6-1/2，36h 月上限 L1 拦截，紧急补单留痕）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_overtime_apply")
public class HrOvertimeApply extends BaseEntity {
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_GM_SPECIAL = "GM_SPECIAL";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_CONFIRMED = "CONFIRMED";

    /** OT+yyyyMMdd+4 位 */
    private String otNo;
    private String empId;
    private String empNo;
    private String empName;
    private LocalDate otDate;
    private String startTime;
    private String endTime;
    private BigDecimal hours;
    private String reason;
    private String projectRef;
    /** 紧急补单 1=是（事后 1 工作日窗口+主管书面说明） */
    private String isEmergency;
    private String emergencyNote;
    /** 特批标记 1=总经理特批（BR-4.7-04 超 36h 放行通道） */
    private String isSpecial;
    /** 特批超限原因（勾选特批必填） */
    private String specialReason;
    private String status;
    private String apprId;
    /** 审批意见（通过/驳回均留痕） */
    private String apprOpinion;
    /** 提交时当月累计小时快照（36h 校验留痕） */
    private BigDecimal monthHoursSnapshot;
}
