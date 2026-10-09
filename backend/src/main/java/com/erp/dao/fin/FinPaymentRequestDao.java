package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinPaymentRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FinPaymentRequestDao extends BaseMapper<FinPaymentRequest> {

    /** 当日最大流水（前缀 PA + yyyyMMdd） */
    @Select("SELECT MAX(CAST(SUBSTR(REQ_NO, LENGTH(#{prefix}) + 1) AS UNSIGNED)) " +
            "FROM erp_fin_payment_request WHERE REQ_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
