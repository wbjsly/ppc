package com.erp.dao.eam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.eam.EamLeaseContract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EamLeaseContractDao extends BaseMapper<EamLeaseContract> {

    /**
     * 当日最大流水（LSE + yyyyMMdd + - 共 12 字符，后缀自第 13 位起；唯一索引兜底并发）。
     */
    @Select("SELECT MAX(CAST(SUBSTR(LSE_NO, 13) AS UNSIGNED)) FROM erp_eam_lease_contract " +
            "WHERE LSE_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
