package com.erp.dao.sd;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ATP 四因子取数（tasks 8.2/8.3，spec sales-atp-reservation BR-4.3-19/20/21）。
 * 集中放 SQL，避免散落实体 DAO。
 */
public interface AtpFactsDao {

    /**
     * 在手与待检（AVAILABLE_QTY 已排除待检/让步锁定；QC_QTY = 锁定量单列展示）。
     * 返回 {A: onHand, Q: excludedQc}。
     */
    @Select("SELECT COALESCE(SUM(AVAILABLE_QTY), 0) AS A, COALESCE(SUM(QC_QTY), 0) AS Q "
            + "FROM erp_inv_stock WHERE WAREHOUSE_CODE = #{warehouseCode} AND ITEM_CODE = #{itemCode}")
    Map<String, Object> stockFact(@Param("itemCode") String itemCode,
                                  @Param("warehouseCode") String warehouseCode);

    /**
     * 寄售库存余量（独立表，排除在 OnHand 之外并单独列示）。
     */
    @Select("SELECT COALESCE(SUM(QTY - ISSUED_QTY), 0) FROM erp_inv_vmi_stock "
            + "WHERE ITEM_CODE = #{itemCode} AND DEL_FLAG = '0'")
    BigDecimal vmiRemain(@Param("itemCode") String itemCode);

    /**
     * 已批 APPROVED PO 未收量明细（按预计到货日升序；到货日 = PROMISE_DATE 优先、否则 REQ_DATE）。
     * 仅 APPROVED 计入——不确定补货（草稿/审批中）不纳入 ATP。
     * 返回行：{arrive: 日期, openQty: 未收量}。
     */
    @Select("SELECT COALESCE(p.PROMISE_DATE, l.REQ_DATE) AS arrive, "
            + "(COALESCE(l.QTY, 0) - COALESCE(l.RECEIVED_QTY, 0)) AS openQty "
            + "FROM erp_proc_po_line l JOIN erp_proc_po p ON l.PO_ID = p.ID "
            + "WHERE p.STATUS = 'APPROVED' AND p.DEL_FLAG = '0' "
            + "AND l.ITEM_CODE = #{itemCode} AND l.RECEIVED_QTY < l.QTY "
            + "ORDER BY arrive")
    List<Map<String, Object>> incomingRows(@Param("itemCode") String itemCode);

    /**
     * 近 90 天已过账领用出库量（日均销量的确定数据源；发货域组 9 上线后可切换 SO 已发量）。
     */
    @Select("SELECT COALESCE(SUM(l.QTY), 0) FROM erp_inv_material_issue_line l "
            + "JOIN erp_inv_material_issue h ON l.ISSUE_ID = h.ID "
            + "WHERE l.ITEM_CODE = #{itemCode} AND h.STATUS = 'POSTED' "
            + "AND h.POST_DATE >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)")
    BigDecimal issuedLast90(@Param("itemCode") String itemCode);
}
