package com.erp.dao.scm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.scm.CriticalGroup;
import org.apache.ibatis.annotations.Mapper;

/** 关键物料组白名单 DAO（add-partner-onboarding） */
@Mapper
public interface CriticalGroupDao extends BaseMapper<CriticalGroup> {
}
