package com.erp.dao.scm;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.scm.PartnerActivateToken;
import org.apache.ibatis.annotations.Mapper;

/** 激活 token DAO（add-partner-onboarding） */
@Mapper
public interface PartnerActivateTokenDao extends BaseMapper<PartnerActivateToken> {
}
