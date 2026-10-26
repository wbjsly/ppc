package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 考勤操作日志（批量操作留痕，不可软删语义同 hr_change_log）。 */
@Data
@TableName("hr_attendance_op_log")
public class HrAttendanceOpLog {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** APPEAL/MAKEUP/MGR_CONFIRM/HR_FINAL/CORRECT/RERUN */
    private String opType;
    private String operator;
    private LocalDateTime opAt;
    /** 涉及异常/申请 ID 数组 JSON */
    private String applicableIdsJson;
    private String reason;
}
