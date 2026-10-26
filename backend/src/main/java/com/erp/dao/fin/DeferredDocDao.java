package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.DeferredDoc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeferredDocDao extends BaseMapper<DeferredDoc> {

    /** 同期最大流水（待摊单号 DEF+yyyyMMdd 推号） */
    @Select("SELECT MAX(CAST(SUBSTRING(DEF_NO, 11) AS UNSIGNED)) FROM erp_fin_deferred_doc "
            + "WHERE DEF_NO LIKE CONCAT(#{prefix}, '%') AND DEL_FLAG = '0'")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
