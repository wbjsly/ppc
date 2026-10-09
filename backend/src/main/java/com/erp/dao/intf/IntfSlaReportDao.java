package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfSlaReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfSlaReportDao extends BaseMapper<IntfSlaReport> {

    @Select("SELECT * FROM erp_intf_sla_report WHERE MONTH_TAG = #{monthTag} AND DEL_FLAG = '0' " +
            "ORDER BY VERSION DESC LIMIT 1")
    IntfSlaReport selectByMonth(@Param("monthTag") String monthTag);
}
