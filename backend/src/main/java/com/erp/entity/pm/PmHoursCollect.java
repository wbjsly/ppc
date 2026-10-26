package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 工时归集明细（add-timesheet-management，FR-4.8-4-3；只增不改，费率快照）。 */
@Getter
@Setter
@TableName("erp_pm_hours_collect")
public class PmHoursCollect extends BaseEntity {

    /** 职级来源 */
    public static final String SRC_TASK = "TASK";
    public static final String SRC_PROJECT = "PROJECT";
    public static final String SRC_CURRENT = "CURRENT";

    /** 来源工时单 */
    private String timesheetId;
    /** 来源明细行 */
    private String lineId;
    /** 员工 */
    private String empId;
    /** 员工姓名 */
    private String empName;
    /** 职级快照 */
    private String grade;
    /** 费率来源（TASK|PROJECT|CURRENT，审计） */
    private String gradeSource;
    /** 项目 */
    private String projectId;
    /** WBS 任务 */
    private String wbsId;
    /** 工时日期快照（费率匹配基准） */
    private LocalDate workDate;
    /** 工时 */
    private BigDecimal hours;
    /** 费率快照 */
    private BigDecimal rate;
    /** 人工成本 = HOURS × RATE */
    private BigDecimal laborCost;
    /** 归集日 */
    private LocalDate collectDate;
}
