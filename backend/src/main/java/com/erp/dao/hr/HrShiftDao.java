package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrShift;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrShiftDao extends BaseMapper<HrShift> {
    @Select("SELECT SHIFT_NO FROM hr_shift WHERE SHIFT_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
