package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.ArStatement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ArStatementDao extends BaseMapper<ArStatement> {

    /** 既有对账单号（ST-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT STMT_NO FROM erp_fin_ar_statement WHERE STMT_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 同客户同期间对账单（唯一键冲突前的幂等查询） */
    @Select("SELECT ID FROM erp_fin_ar_statement WHERE CUSTOMER_ID = #{customerId} "
            + "AND PERIOD = #{period} AND DEL_FLAG='0'")
    String selectByCustomerPeriod(@Param("customerId") String customerId,
                                  @Param("period") String period);
}
