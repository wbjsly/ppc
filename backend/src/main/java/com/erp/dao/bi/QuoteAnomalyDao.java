package com.erp.dao.bi;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 比价异常清单（spec price-monitoring，BR-4.2-12 只读消费既有比价数据）。
 * 偏离率 = |单价 - 同 RFQ 有效报价均值| / 均值 × 100。
 */
@Mapper
public interface QuoteAnomalyDao {

    @Select("SELECT q.ID AS id, q.RFQ_ID AS rfqId, q.SUPPLIER_ID AS supplierId, " +
            "  q.UNIT_PRICE AS unitPrice, q.ANOMALY_FLAG AS anomalyFlag, " +
            "  q.ANOMALY_CONFIRMED AS anomalyConfirmed, q.EXCLUDED AS excluded, " +
            "  (SELECT AVG(q2.UNIT_PRICE) FROM erp_proc_quote q2 " +
            "   WHERE q2.RFQ_ID = q.RFQ_ID AND q2.UNIT_PRICE IS NOT NULL " +
            "   AND (q2.EXCLUDED IS NULL OR q2.EXCLUDED = 0)) AS avgPrice, " +
            "  ABS(q.UNIT_PRICE - (SELECT AVG(q2.UNIT_PRICE) FROM erp_proc_quote q2 " +
            "   WHERE q2.RFQ_ID = q.RFQ_ID AND q2.UNIT_PRICE IS NOT NULL)) " +
            "   / NULLIF((SELECT AVG(q2.UNIT_PRICE) FROM erp_proc_quote q2 " +
            "   WHERE q2.RFQ_ID = q.RFQ_ID AND q2.UNIT_PRICE IS NOT NULL), 0) * 100 AS deviationPct " +
            "FROM erp_proc_quote q " +
            "WHERE q.UNIT_PRICE IS NOT NULL " +
            "HAVING deviationPct > 20 OR anomalyFlag = 1 " +
            "ORDER BY deviationPct DESC LIMIT 200")
    List<Map<String, Object>> anomalies();

    /** 价控日志（按物料最近一条，关联展示） */
    @Select("SELECT ID AS id, PO_NO AS poNo, ITEM_CODE AS itemCode, CHECK_LEVEL AS checkLevel, " +
            "  BASE_VALUE AS baseValue, ACTUAL_VALUE AS actualValue, RESULT AS result, CHK_DATE AS chkDate " +
            "FROM erp_proc_po_price_control WHERE ITEM_CODE = #{itemCode} AND DEL_FLAG = '0' " +
            "ORDER BY CHK_DATE DESC LIMIT 1")
    Map<String, Object> latestPriceControl(@Param("itemCode") String itemCode);
}
