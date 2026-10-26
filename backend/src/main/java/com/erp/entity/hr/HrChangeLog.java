package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 主档变更日志（BR-4.7-07 留痕，不可删；无 BaseEntity——日志不可软删）。 */
@Data
@TableName("hr_change_log")
public class HrChangeLog {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String empId;
    /** 字段中文名 */
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String reason;
    private String changeBy;
    private LocalDateTime changeAt;
}
