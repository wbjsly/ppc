package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.AmortPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface AmortPlanDao extends BaseMapper<AmortPlan> {

    /** 期间未生成计划行（结账⑨闸口 + generate 扫描源） */
    @Select("SELECT * FROM erp_fin_amort_plan WHERE PERIOD = #{period} AND GEN_STATUS = 'NOT_GEN' "
            + "AND DEL_FLAG = '0' ORDER BY PLAN_TYPE, DOC_NO")
    List<AmortPlan> selectNotGenerated(@Param("period") String period);

    /** 期间已生成行数（generate 幂等返回 skipped 计数） */
    @Select("SELECT COUNT(*) FROM erp_fin_amort_plan WHERE PERIOD = #{period} "
            + "AND GEN_STATUS = 'GENERATED' AND DEL_FLAG = '0'")
    long countGenerated(@Param("period") String period);

    /** 某单据计划合计（L1：合计=登记总额校验，FR-4.6-8-2） */
    @Select("SELECT COALESCE(SUM(AMOUNT), 0) FROM erp_fin_amort_plan "
            + "WHERE DOC_ID = #{docId} AND DEL_FLAG = '0'")
    BigDecimal sumByDoc(@Param("docId") String docId);

    /** 某单据计划行 */
    @Select("SELECT * FROM erp_fin_amort_plan WHERE DOC_ID = #{docId} AND DEL_FLAG = '0' "
            + "ORDER BY PERIOD")
    List<AmortPlan> selectByDoc(@Param("docId") String docId);

    /** 未摊销计划剩余（AMORT 未生成行合计，年末勾稽 4.4） */
    @Select("SELECT COALESCE(SUM(AMOUNT), 0) FROM erp_fin_amort_plan "
            + "WHERE PLAN_TYPE = 'AMORT' AND GEN_STATUS = 'NOT_GEN' AND DEL_FLAG = '0'")
    BigDecimal remainingAmortPlan();

    /** 结账⑧后续第⑨项：全库未生成计划行数（按期间，FinPeriodServiceImpl 用） */
    @Select("SELECT COUNT(*) FROM erp_fin_amort_plan WHERE GEN_STATUS = 'NOT_GEN' AND DEL_FLAG = '0'")
    long countAllNotGenerated();

    @Select("SELECT COUNT(*) FROM erp_fin_amort_plan WHERE PERIOD = #{period} "
            + "AND GEN_STATUS = 'NOT_GEN' AND DEL_FLAG = '0'")
    long countNotGenerated(@Param("period") String period);
}
