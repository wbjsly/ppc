package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 请假单（日粒度占用排班日，余额校验 L1，扣款计入月度汇总）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_leave_apply")
public class HrLeaveApply extends BaseEntity {
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVING = "APPROVING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_CANCELLED = "CANCELLED";

    /** LV+yyyyMMdd+4 位 */
    private String lvNo;
    private String empId;
    private String empNo;
    private String empName;
    private String deptId;
    private String typeCode;
    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal duration;
    /** DAY / HOUR */
    private String durationUnit;
    private String reason;
    /** 工作代理人 */
    private String delegate;
    private String status;
    private String apprId;
    /** 日工资基数快照（BASE_SALARY/21.75） */
    private BigDecimal dailyBase;
    /** 扣款快照 = 基数×系数×时长 */
    private BigDecimal deductAmount;
    /** 余额批次扣减留痕 JSON */
    private String balanceConsumeJson;
}
