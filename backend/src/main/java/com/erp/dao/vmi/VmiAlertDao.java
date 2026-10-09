package com.erp.dao.vmi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.vmi.VmiAlert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VmiAlertDao extends BaseMapper<VmiAlert> {

    /** 告警号流水：VA+yyyyMM+-000001 */
    @Select("SELECT MAX(CAST(SUBSTR(ALERT_NO, 10) AS UNSIGNED)) FROM erp_proc_vmi_alert " +
            "WHERE ALERT_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
