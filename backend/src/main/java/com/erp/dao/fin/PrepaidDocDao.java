package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.PrepaidDoc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PrepaidDocDao extends BaseMapper<PrepaidDoc> {

    /** 同期最大流水（预提单号 PRE+yyyyMMdd 推号） */
    @Select("SELECT MAX(CAST(SUBSTRING(PRE_NO, 11) AS UNSIGNED)) FROM erp_fin_prepaid_doc "
            + "WHERE PRE_NO LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG = '0'")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
