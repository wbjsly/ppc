package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 月度考勤汇总锁（BR-4.7-18 终审后不可改；更正解锁单员工行）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_attendance_month_lock")
public class HrAttendanceMonthLock extends BaseEntity {
    /** yyyyMM */
    private String period;
    private String empId;
    private LocalDateTime lockedAt;
    private String lockedBy;
    private LocalDateTime correctAt;
    private String correctBy;
    private String remark;
}
