package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.GoodsReceipt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface GoodsReceiptDao extends BaseMapper<GoodsReceipt> {

    /**
     * 当月最大流水（忽略软删空洞，唯一索引兜底并发）。
     * 前缀 GR + yyyyMM + - 共 9 字符，后缀自第 10 位起（6 位流水）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(GR_NO, 10) AS UNSIGNED)) FROM erp_proc_gr " +
            "WHERE GR_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
