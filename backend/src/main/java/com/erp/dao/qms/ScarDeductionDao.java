package com.erp.dao.qms;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.qms.ScarDeduction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScarDeductionDao extends BaseMapper<ScarDeduction> {

    /**
     * 当月最大流水（忽略软删空洞，唯一索引兜底并发）。
     * 前缀 = SD + yyyyMMdd + '-' 共 11 字符，后缀自第 12 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(DEDUCT_NO, 12) AS UNSIGNED)) FROM erp_qms_scar_deduction " +
            "WHERE DEDUCT_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
