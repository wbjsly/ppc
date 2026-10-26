package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 排班日历（M10-3-1，员工×日期×班次/休息；同人同日重排以最后一次为准）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_schedule")
public class HrSchedule extends BaseEntity {
    public static final String SRC_SINGLE = "SINGLE";
    public static final String SRC_BULK = "BULK";
    public static final String SRC_COPY = "COPY";

    private String empId;
    private LocalDate workDate;
    /** 班次（IS_REST=1 时为空） */
    private String shiftId;
    /** 休息日 1=是 */
    private String isRest;
    /** SINGLE/BULK/COPY */
    private String source;
    private String remark;
}
