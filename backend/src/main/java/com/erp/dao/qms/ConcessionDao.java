package com.erp.dao.qms;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.qms.Concession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConcessionDao extends BaseMapper<Concession> {

    /**
     * 当月最大流水（忽略软删空洞，唯一索引兜底并发）。
     * 前缀 = CC + yyyyMMdd + '-' 共 11 字符，后缀自第 12 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(CONCESSION_NO, 12) AS UNSIGNED)) FROM erp_qms_concession " +
            "WHERE CONCESSION_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);
}
