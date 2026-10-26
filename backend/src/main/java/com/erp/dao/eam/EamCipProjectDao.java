package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamCipProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EamCipProjectDao extends BaseMapper<EamCipProject> {

    /**
     * 当日最大流水（唯一索引兜底并发）。
     * 前缀 = CIP + yyyyMMdd + '-' 共 12 字符，后缀自第 13 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(PROJECT_NO, 13) AS UNSIGNED)) FROM erp_eam_cip_project " +
            "WHERE PROJECT_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
