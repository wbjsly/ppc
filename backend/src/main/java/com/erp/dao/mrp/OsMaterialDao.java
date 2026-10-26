package com.erp.dao.mrp;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.mrp.OsMaterial;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 外协发料单 DAO（change add-outsourcing-processing，迁移 126）。
 */
public interface OsMaterialDao extends BaseMapper<OsMaterial> {

    /**
     * 发料单流水：OSM+yyyyMMdd+NNNNNN（前缀 11 字符），取号后缀数字最大值（含逻辑删行，防撞号）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(OSM_NO, 12) AS UNSIGNED)) FROM erp_mrp_os_material " +
            "WHERE OSM_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
