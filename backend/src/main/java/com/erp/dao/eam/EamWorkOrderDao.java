package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamWorkOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EamWorkOrderDao extends BaseMapper<EamWorkOrder> {

    /**
     * 当日最大流水（WO + yyyyMMdd + - 共 11 字符，后缀自第 12 位起；唯一索引兜底并发）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(WO_NO, 12) AS UNSIGNED)) FROM erp_eam_work_order " +
            "WHERE WO_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
