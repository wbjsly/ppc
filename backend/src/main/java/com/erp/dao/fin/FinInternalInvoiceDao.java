package com.erp.dao.fin;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.fin.FinInternalInvoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FinInternalInvoiceDao extends BaseMapper<FinInternalInvoice> {

    /** 票号流水（IT-YYYYMMDD-NNNN 前缀） */
    @Select("SELECT INT_INV_NO FROM erp_fin_internal_invoice WHERE INT_INV_NO LIKE #{prefix}")
    List<String> selectNosByPrefix(@Param("prefix") String prefix);

    /** 内部往来余额视图：ISSUED 未核销按法人对+方向汇总（spec internal-transfer-accounting F2） */
    @Select("SELECT OUT_LE_CODE AS outLe, IN_LE_CODE AS inLe, DIRECTION AS direction, "
            + "COUNT(*) AS issuedCount, COALESCE(SUM(TOTAL_AMOUNT), 0) AS issuedAmount "
            + "FROM erp_fin_internal_invoice "
            + "WHERE STATUS = 'ISSUED' AND DEL_FLAG = '0' "
            + "GROUP BY OUT_LE_CODE, IN_LE_CODE, DIRECTION "
            + "ORDER BY OUT_LE_CODE, IN_LE_CODE, DIRECTION")
    List<java.util.Map<String, Object>> sumIssuedByPair();
}
