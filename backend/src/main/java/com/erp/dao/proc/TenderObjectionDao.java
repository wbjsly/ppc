package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.TenderObjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenderObjectionDao extends BaseMapper<TenderObjection> {

    /**
     * 当日最大流水。前缀 OBJ-YYYYMMDD- 共 13 字符，后缀自第 14 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(OBJECTION_NO, 14) AS UNSIGNED)) FROM erp_proc_tender_objection " +
            "WHERE OBJECTION_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
