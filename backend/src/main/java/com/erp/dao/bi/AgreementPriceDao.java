package com.erp.dao.bi;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 框架协议价参考线（只读；共享主数据不做行级法人过滤）。 */
@Mapper
public interface AgreementPriceDao {

    @Select("SELECT l.ITEM_CODE AS itemCode, l.UNIT_PRICE AS unitPrice, 'CNY' AS currency, "
            + "a.PA_CODE AS agreementNo, a.STATUS AS status "
            + "FROM erp_mdm_price_agreement_line l "
            + "JOIN erp_mdm_price_agreement a ON a.ID = l.PA_ID AND a.DEL_FLAG = '0' "
            + "WHERE l.ITEM_CODE = #{itemCode} AND l.DEL_FLAG = '0' "
            + "AND a.STATUS IN ('ACTIVE','EFFECTIVE') LIMIT 20")
    List<Map<String, Object>> listByItem(@Param("itemCode") String itemCode);

    /** 快照月度均价（协议价偏离比对用） */
    @Select("SELECT ITEM_CODE AS itemCode, SUPPLIER_ID AS supplierId, CATEGORY_CODE AS categoryCode, " +
            "  SUM(PO_AMT) / NULLIF(SUM(PO_QTY),0) AS avgPrice " +
            "FROM erp_bi_cost_snapshot WHERE MONTH_TAG = #{monthTag} AND PO_QTY > 0 AND DEL_FLAG = '0' " +
            "GROUP BY ITEM_CODE, SUPPLIER_ID, CATEGORY_CODE")
    List<Map<String, Object>> pricesOfMonths(@Param("monthTag") String monthTag);
}
