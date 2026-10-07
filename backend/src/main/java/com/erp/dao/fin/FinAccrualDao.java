package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinAccrual;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FinAccrualDao extends BaseMapper<FinAccrual> {

    /** 当日最大流水（前缀 = AC + yyyyMMdd 共 10 字符） */
    @Select("SELECT MAX(CAST(SUBSTR(ACCRUAL_NO, LENGTH(#{prefix}) + 1) AS UNSIGNED)) " +
            "FROM erp_fin_accrual WHERE ACCRUAL_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
