package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 考勤异常（状态机：IDENTIFIED→APPEALED→MGR_CONFIRMED→HR_FINAL→CORRECTED）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_attendance_exception")
public class HrAttendanceException extends BaseEntity {
    public static final String TYPE_LATE = "LATE";
    public static final String TYPE_EARLY = "EARLY";
    public static final String TYPE_MISSING = "MISSING";
    public static final String TYPE_ABSENT = "ABSENT";

    public static final String ST_IDENTIFIED = "IDENTIFIED";
    public static final String ST_APPEALED = "APPEALED";
    public static final String ST_MGR_CONFIRMED = "MGR_CONFIRMED";
    public static final String ST_HR_FINAL = "HR_FINAL";
    public static final String ST_CORRECTED = "CORRECTED";

    private String empId;
    /** 工号/姓名/部门快照（报表展示） */
    private String empNo;
    private String empName;
    private String deptId;
    private LocalDate excDate;
    /** LATE/EARLY/MISSING/ABSENT */
    private String excType;
    /** 迟到/早退分钟数 */
    private Integer detailMin;
    private String status;
    private String appealReason;
    /** 佐证（系统内单据引用或文字） */
    private String evidenceRef;
    private LocalDateTime appealAt;
    private String confirmOpinion;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String finalBy;
    private LocalDateTime finalAt;
    /** 更正申请审批锚 */
    private String correctApprId;
}
