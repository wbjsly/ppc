package com.erp.dao.hr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.hr.HrSocialChange;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HrSocialChangeDao extends BaseMapper<HrSocialChange> {

    /** 按前缀取单号（nextNo 用）。 */
    @Select("SELECT CHG_NO FROM hr_social_change WHERE CHG_NO LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG='0'")
    List<String> selectNosByPrefix(String prefix);
}
