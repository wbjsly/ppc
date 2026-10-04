package com.erp.dao.ops;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.ops.MdmOutboxEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MdmOutboxDao extends BaseMapper<MdmOutboxEvent> {
}
