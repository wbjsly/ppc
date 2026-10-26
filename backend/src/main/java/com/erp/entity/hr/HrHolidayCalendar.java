package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** 法定假日日历（手工维护；预置 2026-2027；加班倍数与异常豁免消费方）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_holiday_calendar")
public class HrHolidayCalendar extends BaseEntity {
    public static final String TYPE_WORKDAY = "WORKDAY";
    public static final String TYPE_RESTDAY = "RESTDAY";
    public static final String TYPE_STATUTORY = "STATUTORY";

    private LocalDate holidayDate;
    private String name;
    /** WORKDAY/RESTDAY/STATUTORY */
    private String type;
    private String remark;
}
