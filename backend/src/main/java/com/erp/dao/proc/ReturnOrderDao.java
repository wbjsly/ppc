package com.erp.dao.proc;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.proc.ReturnOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReturnOrderDao extends BaseMapper<ReturnOrder> {

    /**
     * 当月最大流水（忽略软删空洞，唯一索引兜底并发）。
     * 前缀 = RT + yyyyMMdd + '-' 共 11 字符，后缀自第 12 位起。
     */
    @Select("SELECT MAX(CAST(SUBSTR(RETURN_NO, 12) AS UNSIGNED)) FROM erp_proc_return " +
            "WHERE RETURN_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 红字入库凭证当月最大流水：前缀 = RV + yyyyMMdd + '-' 共 11 字符 */
    @Select("SELECT MAX(CAST(SUBSTR(RED_DOC_NO, 12) AS UNSIGNED)) FROM erp_proc_return " +
            "WHERE RED_DOC_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxRedSeq(@Param("prefix") String prefix);
}
