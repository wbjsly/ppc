package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.ReceiptDifference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReceiptDifferenceDao extends BaseMapper<ReceiptDifference> {

    /** 当日最大流水：前缀 GDF-YYYYMMDD- 共 13 字符，后缀自第 14 位起（3 位） */
    @Select("SELECT MAX(CAST(SUBSTR(DIFF_NO, 14) AS UNSIGNED)) FROM erp_proc_gr_difference " +
            "WHERE DIFF_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
