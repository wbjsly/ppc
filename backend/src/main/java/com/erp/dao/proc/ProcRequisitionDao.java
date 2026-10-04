package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.ProcRequisition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProcRequisitionDao extends BaseMapper<ProcRequisition> {

    /**
     * 当日最大流水（忽略 DEL_FLAG，避免软删空洞导致单号复用撞唯一索引）。
     * 前缀 PR-YYYYMMDD- 共 12 字符，后缀自第 13 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(PR_NO, 13) AS UNSIGNED)) FROM erp_proc_requisition " +
            "WHERE PR_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
