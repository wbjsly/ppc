package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TpAssessment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TpAssessmentDao extends BaseMapper<TpAssessment> {

    /** 既有自评单号（TPA 前缀流水） */
    @Select("SELECT ASSESS_NO FROM erp_fin_tp_assessment WHERE ASSESS_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
