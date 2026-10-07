package com.erp.dao.sd;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.sd.So;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface SoDao extends BaseMapper<So> {

    /** 既有订单编号流水（SO-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT SO_NO FROM erp_sd_so WHERE SO_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /**
     * 未清 SO 预占金额（BR-4.3-13 因子③）：该客户未关闭/未冻结 SO 合计，
     * 排除自身（excludeSoId 可空）。信用冻结态不重复占用（已冻结单不再计预占）。
     */
    @Select("SELECT COALESCE(SUM(TOTAL_AMOUNT), 0) FROM erp_sd_so "
            + "WHERE CUSTOMER_ID = #{customerId} AND DEL_FLAG='0' "
            + "AND STATUS NOT IN ('CLOSED','CANCELLED','CREDIT_FREEZE') "
            + "AND (#{excludeSoId} IS NULL OR ID <> #{excludeSoId})")
    BigDecimal selectOpenReserved(@Param("customerId") String customerId,
                                  @Param("excludeSoId") String excludeSoId);
}
