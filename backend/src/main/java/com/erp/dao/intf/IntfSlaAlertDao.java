package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfSlaAlert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfSlaAlertDao extends BaseMapper<IntfSlaAlert> {

    /** 同 ALERT_KEY 最近一条告警（连续 Critical 计数与升级判定用） */
    @Select("SELECT * FROM erp_intf_sla_alert WHERE ALERT_KEY = #{alertKey} AND DEL_FLAG = '0' " +
            "ORDER BY ALERT_AT DESC LIMIT 1")
    IntfSlaAlert selectLatest(@Param("alertKey") String alertKey);

    @Select("SELECT COUNT(*) FROM erp_intf_sla_alert WHERE ALERT_KEY = #{alertKey} AND DEL_FLAG = '0' " +
            "AND LEVEL = 'CRITICAL' AND ALERT_AT >= #{from}")
    int countCriticalSince(@Param("alertKey") String alertKey, @Param("from") java.time.LocalDateTime from);

    @Select("SELECT COUNT(*) FROM erp_intf_sla_alert WHERE STATUS = 'OPEN' AND DEL_FLAG = '0'")
    int countOpen();

    @Select("SELECT * FROM erp_intf_sla_alert WHERE ALERT_KEY = #{alertKey} AND ALERT_WINDOW = #{window} " +
            "AND DEL_FLAG = '0' LIMIT 1")
    IntfSlaAlert selectByKeyWindow(@Param("alertKey") String alertKey,
                                   @Param("window") java.time.LocalDateTime window);

    /** 同指标最近 N 分钟内的 Critical 次数（连续 3 次升级判定，BR-4.9-28） */
    @Select("SELECT COUNT(*) FROM erp_intf_sla_alert WHERE ALERT_KEY = #{alertKey} AND LEVEL IN ('CRITICAL','EMERGENCY') " +
            "AND ALERT_AT >= #{from} AND DEL_FLAG = '0'")
    int countCriticalWithin(@Param("alertKey") String alertKey, @Param("from") java.time.LocalDateTime from);
}
