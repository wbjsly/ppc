package com.erp.dao.scm;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 记分卡四维采集（spec supplier-scorecard FR-4.9-7-2：自动抽取不可手工修改）。
 * 窗口 = [monthStart, nextMonthStart)；全部按 SUPPLIER_ID 分组。
 */
@Mapper
public interface ScorecardCollectDao {

    /** 质量：来料批次合格率 + PPM（PASS/FAIL/PENDING 按 RESULT 判定；PENDING 不计分母外剔除） */
    @Select("SELECT SUPPLIER_ID AS supplierId, " +
            "  COUNT(*) AS totalLots, " +
            "  SUM(CASE WHEN RESULT = 'PASS' THEN 1 ELSE 0 END) AS passLots, " +
            "  SUM(CASE WHEN RESULT = 'FAIL' THEN COALESCE(LOT_QTY,0) ELSE 0 END) AS failQty, " +
            "  SUM(COALESCE(LOT_QTY,0)) AS totalQty " +
            "FROM erp_qms_inspection_lot " +
            "WHERE DEL_FLAG = '0' AND RESULT IN ('PASS','FAIL') " +
            "AND CREATE_DATE >= #{start} AND CREATE_DATE < #{next} " +
            "GROUP BY SUPPLIER_ID")
    List<Map<String, Object>> quality(@Param("start") String start, @Param("next") String next);

    /** 交付：准时交付率 = 实际到货 ≤ PO 承诺交期（GR.ARRIVAL_DATE vs PO.PROMISE_DATE） */
    @Select("SELECT p.SUPPLIER_ID AS supplierId, COUNT(*) AS totalLots, " +
            "  SUM(CASE WHEN p.PROMISE_DATE IS NOT NULL AND g.ARRIVAL_DATE IS NOT NULL " +
            "       AND DATE(g.ARRIVAL_DATE) <= DATE(p.PROMISE_DATE) THEN 1 ELSE 0 END) AS onTimeLots " +
            "FROM erp_proc_gr g JOIN erp_proc_po p ON p.ID = g.PO_ID " +
            "WHERE g.DEL_FLAG = '0' AND p.DEL_FLAG = '0' " +
            "AND g.ARRIVAL_DATE >= #{start} AND g.ARRIVAL_DATE < #{next} " +
            "GROUP BY p.SUPPLIER_ID")
    List<Map<String, Object>> delivery(@Param("start") String start, @Param("next") String next);

    /** ASN 准确率 = 行数量与实收一致（QTY = RECEIVED_QTY）比例 */
    @Select("SELECT a.SUPPLIER_ID AS supplierId, COUNT(*) AS totalLines, " +
            "  SUM(CASE WHEN l.QTY = l.RECEIVED_QTY THEN 1 ELSE 0 END) AS accLines " +
            "FROM erp_proc_asn_line l JOIN erp_proc_asn a ON a.ID = l.ASN_ID AND a.DEL_FLAG = '0' " +
            "WHERE l.DEL_FLAG = '0' AND a.CREATE_DATE >= #{start} AND a.CREATE_DATE < #{next} " +
            "GROUP BY a.SUPPLIER_ID")
    List<Map<String, Object>> asnAccuracy(@Param("start") String start, @Param("next") String next);

    /**
     * 响应：PO 48h 确认率 = PROC.PO_CONFIRMED 事件发生时间 ≤ PO 创建 + 48h
     * （事件 PAYLOAD.poNo 关联；无事件即未确认）
     */
    @Select("SELECT p.SUPPLIER_ID AS supplierId, COUNT(*) AS totalPo, " +
            "  SUM(CASE WHEN e.OCCURRED_AT IS NOT NULL " +
            "       AND e.OCCURRED_AT <= DATE_ADD(p.CREATE_DATE, INTERVAL 48 HOUR) " +
            "       THEN 1 ELSE 0 END) AS confirmed48h " +
            "FROM erp_proc_po p " +
            "LEFT JOIN erp_ops_outbox e ON e.EVENT_TYPE = 'PROC.PO_CONFIRMED' AND e.DEL_FLAG = '0' " +
            "  AND JSON_UNQUOTE(JSON_EXTRACT(e.PAYLOAD, '$.poNo')) = p.PO_NO " +
            "WHERE p.DEL_FLAG = '0' AND p.CREATE_DATE >= #{start} AND p.CREATE_DATE < #{next} " +
            "GROUP BY p.SUPPLIER_ID")
    List<Map<String, Object>> poConfirm(@Param("start") String start, @Param("next") String next);

    /** 响应：补货确认及时率 = CONFIRM_AT - CREATE_DATE ≤ 24h */
    @Select("SELECT SUPPLIER_ID AS supplierId, COUNT(*) AS totalCnt, " +
            "  SUM(CASE WHEN CONFIRM_AT IS NOT NULL " +
            "       AND CONFIRM_AT <= DATE_ADD(CREATE_DATE, INTERVAL 24 HOUR) THEN 1 ELSE 0 END) AS timelyCnt " +
            "FROM erp_proc_replenish_confirm " +
            "WHERE DEL_FLAG = '0' AND CREATE_DATE >= #{start} AND CREATE_DATE < #{next} " +
            "GROUP BY SUPPLIER_ID")
    List<Map<String, Object>> replenishTimely(@Param("start") String start, @Param("next") String next);

    /** 成本：价格竞争力指数数据源 = 当月快照各供应商月均价（品类内对比在服务层算） */
    @Select("SELECT SUPPLIER_ID AS supplierId, CATEGORY_CODE AS categoryCode, ITEM_CODE AS itemCode, " +
            "  SUM(PO_AMT) AS amt, SUM(PO_QTY) AS qty " +
            "FROM erp_bi_cost_snapshot WHERE MONTH_TAG = #{monthTag} AND PO_QTY > 0 AND DEL_FLAG = '0' " +
            "GROUP BY SUPPLIER_ID, CATEGORY_CODE, ITEM_CODE")
    List<Map<String, Object>> costBasis(@Param("monthTag") String monthTag);

    /** 活跃供应商（记分卡对象范围：窗口内有采购/收货/检验活动的供应商） */
    @Select("SELECT DISTINCT SUPPLIER_ID AS supplierId FROM erp_proc_po " +
            "WHERE DEL_FLAG = '0' AND CREATE_DATE >= #{start} AND CREATE_DATE < #{next} " +
            "UNION SELECT DISTINCT SUPPLIER_ID FROM erp_proc_gr WHERE DEL_FLAG = '0' " +
            "AND ARRIVAL_DATE >= #{start} AND ARRIVAL_DATE < #{next} " +
            "UNION SELECT DISTINCT SUPPLIER_ID FROM erp_qms_inspection_lot WHERE DEL_FLAG = '0' " +
            "AND CREATE_DATE >= #{start} AND CREATE_DATE < #{next}")
    List<Map<String, Object>> activeSuppliers(@Param("start") String start, @Param("next") String next);
}
