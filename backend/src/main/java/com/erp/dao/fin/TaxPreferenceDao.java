package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TaxPreference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaxPreferenceDao extends BaseMapper<TaxPreference> {

    /** 既有备案号（TPF 前缀流水） */
    @Select("SELECT PREF_NO FROM erp_fin_tax_preference WHERE PREF_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
