package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.LoanWriteoff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface LoanWriteoffDao extends BaseMapper<LoanWriteoff> {

    /** 某报销单的冲销合计（差额结算基准） */
    @Select("SELECT COALESCE(SUM(AMOUNT), 0) FROM erp_fin_loan_writeoff "
            + "WHERE REIMBURSE_ID = #{reimburseId} AND DEL_FLAG = '0'")
    BigDecimal selectSumByReimburse(@Param("reimburseId") String reimburseId);

    /** 某报销单冲销明细 */
    @Select("SELECT * FROM erp_fin_loan_writeoff WHERE REIMBURSE_ID = #{reimburseId} "
            + "AND DEL_FLAG = '0' ORDER BY WRITEOFF_DATE, WO_NO")
    List<LoanWriteoff> selectByReimburse(@Param("reimburseId") String reimburseId);

    /** 同期最大流水（冲销单号 LWO+yyyyMMdd 推号） */
    @Select("SELECT MAX(CAST(SUBSTRING(WO_NO, 12) AS UNSIGNED)) FROM erp_fin_loan_writeoff "
            + "WHERE WO_NO LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG = '0'")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
