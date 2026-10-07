package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinSupplierStatement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FinSupplierStatementDao extends BaseMapper<FinSupplierStatement> {

    /** 当日最大流水（前缀 ST + yyyyMMdd） */
    @Select("SELECT MAX(CAST(SUBSTR(STMT_NO, LENGTH(#{prefix}) + 1) AS UNSIGNED)) " +
            "FROM erp_fin_supplier_statement WHERE STMT_NO LIKE CONCAT(#{prefix}, '%')")
    Integer selectMaxSeq(@Param("prefix") String prefix);

    /** 供应商是否存在未关闭差异对账单（EXCEPTION，冻结付款 C-4.2-15） */
    @Select("SELECT COUNT(*) FROM erp_fin_supplier_statement " +
            "WHERE SUPPLIER_ID = #{supplierId} AND STATUS = 'EXCEPTION' AND DEL_FLAG = '0'")
    int countFrozen(@Param("supplierId") String supplierId);
}
