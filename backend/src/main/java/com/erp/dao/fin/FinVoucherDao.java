package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinVoucher;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FinVoucherDao extends BaseMapper<FinVoucher> {

    /**
     * 同类型同月最大流水（忽略软删空洞，唯一索引兜底并发）。
     * 前缀 = 类型(3) + yyyyMM(6) 共 9 字符，流水自第 10 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(VOUCHER_NO, LENGTH(#{prefix}) + 1) AS UNSIGNED)) " +
            "FROM erp_fin_voucher WHERE VOUCHER_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
