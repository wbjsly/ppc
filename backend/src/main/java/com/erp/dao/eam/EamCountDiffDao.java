package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamCountDiff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EamCountDiffDao extends BaseMapper<EamCountDiff> {

    /** 盘点单未闭环差异（结案前置：全部 CLOSED） */
    @Select("SELECT * FROM erp_eam_count_diff WHERE PLAN_ID = #{planId} AND DEL_FLAG = '0' " +
            "AND STATUS <> 'CLOSED'")
    List<EamCountDiff> selectUnsettledByPlan(@Param("planId") String planId);

    /** 未闭环差异数（结案卡控） */
    @Select("SELECT COUNT(*) FROM erp_eam_count_diff WHERE PLAN_ID = #{planId} " +
            "AND DEL_FLAG = '0' AND STATUS <> 'CLOSED'")
    int countUnsettled(@Param("planId") String planId);

    /** 按类型统计差异（报告汇总：OVER/SHORT/TAG_ISSUE/STATUS_CONFLICT） */
    @Select("SELECT DIFF_TYPE AS diffType, COUNT(*) AS cnt, " +
            "COALESCE(SUM(DIFF_AMOUNT),0) AS amount FROM erp_eam_count_diff " +
            "WHERE PLAN_ID = #{planId} AND DEL_FLAG = '0' GROUP BY DIFF_TYPE")
    List<java.util.Map<String, Object>> sumByType(@Param("planId") String planId);
}
