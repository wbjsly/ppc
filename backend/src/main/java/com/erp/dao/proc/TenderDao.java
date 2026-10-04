package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.Tender;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenderDao extends BaseMapper<Tender> {

    /**
     * 当日最大流水（忽略 DEL_FLAG，避免软删空洞撞唯一索引）。
     * 前缀 TND-YYYYMMDD- 共 13 字符，后缀自第 14 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(TENDER_NO, 14) AS UNSIGNED)) FROM erp_proc_tender " +
            "WHERE TENDER_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
