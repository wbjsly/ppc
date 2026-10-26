package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrDept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrDeptDao extends BaseMapper<HrDept> {
    @Select("SELECT DEPT_NO FROM hr_dept WHERE DEPT_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
