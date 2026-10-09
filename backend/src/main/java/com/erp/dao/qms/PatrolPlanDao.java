package com.erp.dao.qms;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.qms.PatrolPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PatrolPlanDao extends BaseMapper<PatrolPlan> {

    /**
     * 当月最大流水：前缀 = PAT + yyyyMMdd + '-' 共 12 字符，后缀自第 13 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(PLAN_CODE, 13) AS UNSIGNED)) FROM erp_qms_patrol_plan " +
            "WHERE PLAN_CODE LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
