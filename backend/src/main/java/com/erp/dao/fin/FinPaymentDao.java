package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinPayment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface FinPaymentDao extends BaseMapper<FinPayment> {

    /** 当日最大流水（前缀 PY + yyyyMMdd） */
    @Select("SELECT MAX(CAST(SUBSTR(PAY_NO, LENGTH(#{prefix}) + 1) AS UNSIGNED)) " +
            "FROM erp_fin_payment WHERE PAY_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 该申请累计已执行付款（超审批金额 L1 校验用） */
    @Select("SELECT IFNULL(SUM(APPLY_AMOUNT), 0) FROM erp_fin_payment " +
            "WHERE REQ_ID = #{reqId} AND DEL_FLAG = '0'")
    BigDecimal selectExecutedByReq(@Param("reqId") String reqId);
}
