package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.ReceiptAdjustment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReceiptAdjustmentDao extends BaseMapper<ReceiptAdjustment> {

    /** 当日最大流水：前缀 GRADJ-YYYYMMDD- 共 15 字符，后缀自第 16 位起（3 位） */
    @Select("SELECT MAX(CAST(SUBSTR(ADJ_NO, 16) AS UNSIGNED)) FROM erp_proc_gr_adjustment " +
            "WHERE ADJ_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
