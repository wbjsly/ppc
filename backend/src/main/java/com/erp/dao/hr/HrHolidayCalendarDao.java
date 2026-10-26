package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrHolidayCalendar;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface HrHolidayCalendarDao extends BaseMapper<HrHolidayCalendar> {
    @Select("SELECT * FROM hr_holiday_calendar WHERE HOLIDAY_DATE = #{date} AND DEL_FLAG = '0'")
    HrHolidayCalendar selectByDate(@Param("date") LocalDate date);
}
