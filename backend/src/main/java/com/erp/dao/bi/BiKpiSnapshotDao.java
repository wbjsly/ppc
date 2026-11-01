package com.erp.dao.bi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.bi.KpiSnapshot;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BiKpiSnapshotDao extends BaseMapper<KpiSnapshot> {
}
