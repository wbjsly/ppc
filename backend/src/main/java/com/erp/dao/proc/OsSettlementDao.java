package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.OsSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OsSettlementDao extends BaseMapper<OsSettlement> {

    /**
     * 当日最大流水（含逻辑删防撞号，唯一索引兜底并发）。
     * 前缀 OSR + yyyyMMdd 共 11 字符，后缀自第 12 位起（6 位流水）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(OSR_NO, 12) AS UNSIGNED)) FROM erp_proc_os_settlement " +
            "WHERE OSR_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
