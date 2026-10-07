package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.Framework;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FrameworkDao extends BaseMapper<Framework> {

    /** 既有协议编号（FW-YYYYMMDD-NNNN 前缀，调用方拼 %） */
    @Select("SELECT FW_NO FROM erp_sd_framework WHERE FW_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
