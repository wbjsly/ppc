package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrOnboarding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrOnboardingDao extends BaseMapper<HrOnboarding> {
    @Select("SELECT ONB_NO FROM hr_onboarding WHERE ONB_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
