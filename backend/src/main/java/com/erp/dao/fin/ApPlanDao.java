package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ApPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApPlanDao extends BaseMapper<ApPlan> {

    /** 某期间某法人的已批准计划（超计划阻断锚；legalEntityId 可空匹配全法人计划） */
    @Select("SELECT * FROM erp_fin_payment_plan "
            + "WHERE PERIOD = #{period} AND STATUS = 'APPROVED' AND DEL_FLAG = '0' "
            + "AND (LEGAL_ENTITY_ID IS NULL OR LEGAL_ENTITY_ID = '' "
            + "     OR LEGAL_ENTITY_ID = #{legalEntityId}) "
            + "ORDER BY LEGAL_ENTITY_ID IS NULL LIMIT 1")
    ApPlan selectApproved(@Param("period") String period,
                          @Param("legalEntityId") String legalEntityId);

    /** 单号（PPL+period+seq 存在性检查用） */
    @Select("SELECT PLAN_NO FROM erp_fin_payment_plan WHERE PLAN_NO LIKE #{prefix}")
    java.util.List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
