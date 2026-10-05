package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.PurchaseOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PurchaseOrderDao extends BaseMapper<PurchaseOrder> {

    /**
     * 当月最大流水（忽略 DEL_FLAG 软删空洞，唯一索引兜底并发）。
     * 前缀 PO + yyyyMM + - 共 9 字符，后缀自第 10 位起（6 位流水）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(PO_NO, 10) AS UNSIGNED)) FROM erp_proc_po " +
            "WHERE PO_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
