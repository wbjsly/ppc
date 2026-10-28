package com.erp.dao.scm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.scm.PartnerApply;
import org.apache.ibatis.annotations.Mapper;

/** 伙伴引入申请单 DAO（add-partner-onboarding） */
@Mapper
public interface PartnerApplyDao extends BaseMapper<PartnerApply> {
}
