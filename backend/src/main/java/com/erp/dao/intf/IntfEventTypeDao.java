package com.erp.dao.intf;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.intf.IntfEventType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IntfEventTypeDao extends BaseMapper<IntfEventType> {

    @Select("SELECT * FROM erp_intf_event_type WHERE EVENT_TYPE = #{eventType} AND DEL_FLAG = '0' LIMIT 1")
    IntfEventType selectByType(@Param("eventType") String eventType);
}
