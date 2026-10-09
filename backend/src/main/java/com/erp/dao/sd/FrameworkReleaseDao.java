package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.FrameworkRelease;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FrameworkReleaseDao extends BaseMapper<FrameworkRelease> {

    /** 既有下达单号（FWRL-YYYYMMDD-NNNN 前缀，调用方拼 %） */
    @Select("SELECT RELEASE_NO FROM erp_sd_framework_release WHERE RELEASE_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
