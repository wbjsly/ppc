package com.erp.dao.iot;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.iot.IotDevice;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IotDeviceDao extends BaseMapper<IotDevice> {
}
