package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.TpDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TpDocumentDao extends BaseMapper<TpDocument> {

    /** 既有文档编号（TPD 前缀流水） */
    @Select("SELECT DOC_NO FROM erp_fin_tp_document WHERE DOC_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);
}
