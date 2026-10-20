package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamCipCost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface EamCipCostDao extends BaseMapper<EamCipCost> {

    /** 项目归集合计（按类别聚合，供 BR-4.13-08 差额检查与转资原值取数） */
    @Select("SELECT COST_TYPE AS costType, SUM(AMOUNT) AS amount FROM erp_eam_cip_cost " +
            "WHERE PROJECT_ID = #{projectId} AND DEL_FLAG = '0' GROUP BY COST_TYPE")
    List<Map<String, Object>> sumByType(@Param("projectId") String projectId);

    /** 项目归集总额 */
    @Select("SELECT IFNULL(SUM(AMOUNT), 0) FROM erp_eam_cip_cost " +
            "WHERE PROJECT_ID = #{projectId} AND DEL_FLAG = '0'")
    BigDecimal sumTotal(@Param("projectId") String projectId);
}
