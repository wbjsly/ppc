package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.Rfq;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RfqDao extends BaseMapper<Rfq> {

    /**
     * 当日最大流水（忽略 DEL_FLAG，避免软删空洞撞唯一索引）。
     * 前缀 RFQ-YYYYMMDD- 共 13 字符，后缀自第 14 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(RFQ_NO, 14) AS UNSIGNED)) FROM erp_proc_rfq " +
            "WHERE RFQ_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
