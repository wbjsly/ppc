package com.erp.dao.iot;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.iot.IotAlarmEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IotAlarmEventDao extends BaseMapper<IotAlarmEvent> {
}
