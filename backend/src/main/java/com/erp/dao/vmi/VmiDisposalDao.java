package com.erp.dao.vmi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.vmi.VmiDisposal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VmiDisposalDao extends BaseMapper<VmiDisposal> {

    /** 处置建议单号流水：VD+yyyyMM+-000001 */
    @Select("SELECT MAX(CAST(SUBSTR(DISPOSAL_NO, 10) AS UNSIGNED)) FROM erp_proc_vmi_disposal " +
            "WHERE DISPOSAL_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
