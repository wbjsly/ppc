package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.EmergencyRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmergencyRequestDao extends BaseMapper<EmergencyRequest> {

    /**
     * 当日最大流水（忽略 DEL_FLAG，避免软删空洞撞唯一索引）。
     * 前缀 EA-YYYYMMDD- 共 12 字符，后缀自第 13 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(EA_NO, 13) AS UNSIGNED)) FROM erp_proc_emergency " +
            "WHERE EA_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
