package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrEmployee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrEmployeeDao extends BaseMapper<HrEmployee> {
    @Select("SELECT EMP_NO FROM hr_employee WHERE EMP_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 行锁（调岗生效/离职执行防并发） */
    @Select("SELECT * FROM hr_employee WHERE ID = #{id} FOR UPDATE")
    HrEmployee selectForUpdate(@Param("id") String id);
}
