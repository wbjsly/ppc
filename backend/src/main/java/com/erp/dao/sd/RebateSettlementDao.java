package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.RebateSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface RebateSettlementDao extends BaseMapper<RebateSettlement> {

    /** 既有结算单号（RB-YYYYMMDD-NNNN 前缀，调用方拼 %） */
    @Select("SELECT SETTLE_NO FROM erp_sd_rebate_settlement WHERE SETTLE_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /**
     * 某季度已占用返利（预算消耗口径）：非草稿/驳回的结算单累计，
     * 含审批中/已批/已执行（FR-4.3-8-3 累计返利不得超预算）。
     */
    @Select("SELECT COALESCE(SUM(REBATE_AMT),0) FROM erp_sd_rebate_settlement "
            + "WHERE QUARTER = #{quarter} AND DEL_FLAG='0' "
            + "AND STATUS <> 'DRAFT' AND STATUS <> 'REJECTED'")
    BigDecimal selectQuarterConsumed(@Param("quarter") String quarter);
}
