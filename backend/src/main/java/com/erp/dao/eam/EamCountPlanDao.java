package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamCountPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EamCountPlanDao extends BaseMapper<EamCountPlan> {

    /**
     * 当日最大流水（CNT + yyyyMMdd + - 共 12 字符，后缀自第 13 位起；唯一索引兜底并发）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(CNT_NO, 13) AS UNSIGNED)) FROM erp_eam_count_plan " +
            "WHERE CNT_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
