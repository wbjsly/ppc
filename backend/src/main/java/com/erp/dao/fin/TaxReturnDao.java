package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TaxReturn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaxReturnDao extends BaseMapper<TaxReturn> {

    /** 既有申报单号（TRET-YYYYMM-NNNN 前缀） */
    @Select("SELECT RETURN_NO FROM erp_fin_tax_return WHERE RETURN_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 同期同税种已存在申报单（每期每税种一单，重申报走调整单） */
    @Select("SELECT * FROM erp_fin_tax_return WHERE PERIOD = #{period} AND TAX_TYPE = #{taxType} "
            + "AND DEL_FLAG = '0' LIMIT 1")
    TaxReturn selectByPeriodType(@Param("period") String period, @Param("taxType") String taxType);

    /** 上期期末留抵（跨期结转取数，BR-4.14-32） */
    @Select("SELECT END_CREDIT FROM erp_fin_tax_return WHERE PERIOD = #{period} "
            + "AND TAX_TYPE = 'VAT' AND DEL_FLAG = '0' AND STATUS IN ('APPROVED','SUBMITTED','FILED','PAID') "
            + "ORDER BY UPDATE_DATE DESC LIMIT 1")
    java.math.BigDecimal selectPrevEndCredit(@Param("period") String period);
}
