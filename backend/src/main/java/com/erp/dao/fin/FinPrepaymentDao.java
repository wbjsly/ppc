package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinPrepayment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface FinPrepaymentDao extends BaseMapper<FinPrepayment> {

    /** 当日最大流水（前缀 PP + yyyyMMdd） */
    @Select("SELECT MAX(CAST(SUBSTR(PP_NO, LENGTH(#{prefix}) + 1) AS UNSIGNED)) " +
            "FROM erp_fin_prepayment WHERE PP_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 该 PO 已付预付款合计（C-4.2-13 累计校验用） */
    @Select("SELECT IFNULL(SUM(EXECUTED_AMOUNT), 0) FROM erp_fin_prepayment " +
            "WHERE PO_NO = #{poNo} AND DEL_FLAG = '0' " +
            "AND STATUS IN ('APPROVED', 'PAID', 'CLOSED')")
    BigDecimal selectPaidByPo(@Param("poNo") String poNo);

    /** 该 PO 已申请（含草稿/审批中）预付款合计 */
    @Select("SELECT IFNULL(SUM(APPLY_AMOUNT), 0) FROM erp_fin_prepayment " +
            "WHERE PO_NO = #{poNo} AND DEL_FLAG = '0' " +
            "AND STATUS IN ('DRAFT', 'PENDING_APPROVE', 'APPROVED', 'PAID')")
    BigDecimal selectAppliedByPo(@Param("poNo") String poNo);
}
