package com.erp.dao.crm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.crm.Opportunity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface OpportunityDao extends BaseMapper<Opportunity> {

    /** 既有最大商机编号（OPP-YYYYMMDD-NNNN 的流水部分） */
    @Select("SELECT OPP_NO FROM erp_crm_opportunity WHERE OPP_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 漏斗：按阶段聚合在跟商机的数量与金额（BR-4.8-09） */
    @Select("SELECT STAGE AS stage, COUNT(*) AS cnt, COALESCE(SUM(EXPECT_AMOUNT),0) AS amount "
            + "FROM erp_crm_opportunity WHERE DEL_FLAG='0' AND STATUS='OPEN' GROUP BY STAGE")
    List<Map<String, Object>> selectFunnel();

    /** 丢失原因分布（归档商机计入，BR-4.8-09） */
    @Select("SELECT LOSS_CATEGORY AS category, COUNT(*) AS cnt "
            + "FROM erp_crm_opportunity WHERE DEL_FLAG='0' AND STATUS='LOST' GROUP BY LOSS_CATEGORY")
    List<Map<String, Object>> selectLossDistribution();

    /** 已终结商机的销售周期（天）：赢单取 CLOSE_AT，丢失取 LOSS_AT */
    @Select("SELECT DATEDIFF(COALESCE(CLOSE_AT, LOSS_AT), CREATE_DATE) AS days "
            + "FROM erp_crm_opportunity "
            + "WHERE DEL_FLAG='0' AND STATUS IN ('WON','LOST') "
            + "AND COALESCE(CLOSE_AT, LOSS_AT) IS NOT NULL")
    List<Integer> selectClosedCycleDays();
}
