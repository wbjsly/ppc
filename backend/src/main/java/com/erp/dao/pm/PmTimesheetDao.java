package com.erp.dao.pm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.pm.PmTimesheet;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PmTimesheetDao extends BaseMapper<PmTimesheet> {
}
