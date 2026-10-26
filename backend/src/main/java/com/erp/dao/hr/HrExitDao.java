package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrExit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrExitDao extends BaseMapper<HrExit> {
    @Select("SELECT EXIT_NO FROM hr_exit WHERE EXIT_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    @Select("SELECT EXIT_CERT_NO FROM hr_exit WHERE EXIT_CERT_NO LIKE #{prefix}")
    List<String> selectCertNosByPrefix(@Param("prefix") String prefix);
}
