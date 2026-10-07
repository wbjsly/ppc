package com.erp.dao.system;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.system.SysParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysParamDao extends BaseMapper<SysParam> {

    @Select("SELECT * FROM erp_sys_param WHERE PARAM_KEY = #{k} AND DEL_FLAG = '0' LIMIT 1")
    SysParam selectByKey(@Param("k") String key);

    @Select("SELECT * FROM erp_sys_param WHERE DEL_FLAG = '0' ORDER BY PARAM_GROUP, PARAM_KEY")
    List<SysParam> selectAllActive();
}
