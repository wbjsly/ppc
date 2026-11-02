package com.erp.dao.iot;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.iot.IotTelemetry;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IotTelemetryDao extends BaseMapper<IotTelemetry> {
}
