package com.erp.dao.vmi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.vmi.VmiSettlement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VmiSettlementDao extends BaseMapper<VmiSettlement> {

    /** 结算单号流水：VS+yyyyMM+-000001 */
    @Select("SELECT MAX(CAST(SUBSTR(SETTLE_NO, 10) AS UNSIGNED)) FROM erp_proc_vmi_settlement " +
            "WHERE SETTLE_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
