package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 工时明细行（add-timesheet-management；项目/WBS 任务/工时/摘要）。 */
@Getter
@Setter
@TableName("erp_pm_timesheet_line")
public class PmTimesheetLine extends BaseEntity {

    /** 工时单 ID */
    private String timesheetId;
    /** 项目 */
    private String projectId;
    /** WBS 任务（level=3 校验，BR-4.8-18） */
    private String wbsId;
    /** 工时（0.5 步长） */
    private BigDecimal hours;
    /** 工作内容摘要 */
    private String workContent;
}
