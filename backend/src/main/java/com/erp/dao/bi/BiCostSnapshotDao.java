package com.erp.dao.bi;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.erp.entity.bi.BiCostSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BiCostSnapshotDao extends BaseMapper<BiCostSnapshot> {

    /**
     * PO 侧聚合 upsert（tasks 3.4 双口径之一：PO 承诺按下单期间归属）。
     * 法人主体：PO 表无 LEGAL_ENTITY 列 → 恒 LE-DEFAULT（design D2 五维中法人维度值域单法人）；
     * 买家：PO 无采购员列 → CREATE_BY 近似。
     * ON DUPLICATE KEY 依赖 uk_bi_snap_dim(MONTH,LE,ITEM,SUPPLIER)。
     */
    @Update("INSERT INTO erp_bi_cost_snapshot " +
            "(ID, BATCH_NO, MONTH_TAG, LEGAL_ENTITY_ID, CATEGORY_CODE, ITEM_CODE, SUPPLIER_ID, BUYER, " +
            " PO_QTY, PO_AMT, PO_AMT_NO_TAX, TAX_AMT, ESTIMATING, CREATE_BY) " +
            "SELECT UUID(), #{batchNo}, DATE_FORMAT(p.CREATE_DATE, '%Y%m'), 'LE-DEFAULT', " +
            "  COALESCE(i.CATEGORY_CODE, '-'), l.ITEM_CODE, p.SUPPLIER_ID, p.CREATE_BY, " +
            "  COALESCE(SUM(l.QTY),0), COALESCE(SUM(l.AMOUNT),0), " +
            "  COALESCE(SUM(l.AMOUNT / (1 + CASE WHEN COALESCE(l.TAX_RATE, p.TAX_RATE, 0) > 100 " +
            "       THEN COALESCE(l.TAX_RATE, p.TAX_RATE, 0)/100 ELSE COALESCE(l.TAX_RATE, p.TAX_RATE, 0) END)),0), " +
            "  COALESCE(SUM(l.AMOUNT - l.AMOUNT / (1 + CASE WHEN COALESCE(l.TAX_RATE, p.TAX_RATE, 0) > 100 " +
            "       THEN COALESCE(l.TAX_RATE, p.TAX_RATE, 0)/100 ELSE COALESCE(l.TAX_RATE, p.TAX_RATE, 0) END)),0), " +
            "  0, 'bi-extract' " +
            "FROM erp_proc_po_line l " +
            "JOIN erp_proc_po p ON p.ID = l.PO_ID AND p.DEL_FLAG = '0' " +
            "LEFT JOIN erp_mdm_item i ON i.ITEM_CODE = l.ITEM_CODE " +
            "WHERE p.CREATE_DATE >= #{monthStart} AND p.CREATE_DATE < #{nextMonthStart} " +
            "GROUP BY DATE_FORMAT(p.CREATE_DATE, '%Y%m'), l.ITEM_CODE, p.SUPPLIER_ID, " +
            "         COALESCE(i.CATEGORY_CODE, '-'), p.CREATE_BY " +
            "ON DUPLICATE KEY UPDATE " +
            "  BATCH_NO = VALUES(BATCH_NO), CATEGORY_CODE = VALUES(CATEGORY_CODE), BUYER = VALUES(BUYER), " +
            "  PO_QTY = VALUES(PO_QTY), PO_AMT = VALUES(PO_AMT), PO_AMT_NO_TAX = VALUES(PO_AMT_NO_TAX), " +
            "  TAX_AMT = VALUES(TAX_AMT), ESTIMATING = 1")
    int upsertFromPo(@Param("batchNo") String batchNo,
                     @Param("monthStart") String monthStart,
                     @Param("nextMonthStart") String nextMonthStart);

    /**
     * 发票侧聚合 upsert（发票实付按入账期间归属，三方匹配后的 AP 发票）。
     * ESTIMATING=0：发票已入账，DIFF_AMT 一并回写。
     */
    @Update("INSERT INTO erp_bi_cost_snapshot " +
            "(ID, BATCH_NO, MONTH_TAG, LEGAL_ENTITY_ID, CATEGORY_CODE, ITEM_CODE, SUPPLIER_ID, " +
            " INV_QTY, INV_AMT, INV_AMT_NO_TAX, INV_TAX, DIFF_AMT, ESTIMATING, CREATE_BY) " +
            "SELECT UUID(), #{batchNo}, DATE_FORMAT(i.INVOICE_DATE, '%Y%m'), 'LE-DEFAULT', " +
            "  COALESCE(md.CATEGORY_CODE, '-'), il.ITEM_CODE, i.SUPPLIER_ID, " +
            "  COALESCE(SUM(il.QTY),0), COALESCE(SUM(il.AMOUNT),0), " +
            "  COALESCE(SUM(il.AMOUNT),0), 0, 0, 0, 'bi-extract' " +
            "FROM erp_fin_ap_invoice_line il " +
            "JOIN erp_fin_ap_invoice i ON i.ID = il.INVOICE_ID AND i.DEL_FLAG = '0' " +
            "LEFT JOIN erp_mdm_item md ON md.ITEM_CODE = il.ITEM_CODE " +
            "WHERE i.INVOICE_DATE >= #{monthStart} AND i.INVOICE_DATE < #{nextMonthStart} AND il.DEL_FLAG = '0' " +
            "GROUP BY DATE_FORMAT(i.INVOICE_DATE, '%Y%m'), il.ITEM_CODE, i.SUPPLIER_ID, " +
            "         COALESCE(md.CATEGORY_CODE, '-') " +
            "ON DUPLICATE KEY UPDATE " +
            "  BATCH_NO = VALUES(BATCH_NO), CATEGORY_CODE = VALUES(CATEGORY_CODE), " +
            "  INV_QTY = VALUES(INV_QTY), INV_AMT = VALUES(INV_AMT), " +
            "  INV_TAX = VALUES(INV_TAX), ESTIMATING = 0, " +
            "  DIFF_AMT = VALUES(INV_AMT) - COALESCE(PO_AMT, 0)")
    int upsertFromInvoice(@Param("batchNo") String batchNo,
                          @Param("monthStart") String monthStart,
                          @Param("nextMonthStart") String nextMonthStart);

    /** 退货红字当期冲减（RETURN_AMT 正数表示扣减） */
    @Update("INSERT INTO erp_bi_cost_snapshot " +
            "(ID, BATCH_NO, MONTH_TAG, LEGAL_ENTITY_ID, CATEGORY_CODE, ITEM_CODE, SUPPLIER_ID, " +
            " RETURN_AMT, ESTIMATING, CREATE_BY) " +
            "SELECT UUID(), #{batchNo}, DATE_FORMAT(r.OUT_DATE, '%Y%m'), 'LE-DEFAULT', " +
            "  COALESCE(md.CATEGORY_CODE, '-'), rl.ITEM_CODE, r.SUPPLIER_ID, " +
            "  COALESCE(SUM(rl.AMOUNT),0), 1, 'bi-extract' " +
            "FROM erp_proc_return_line rl " +
            "JOIN erp_proc_return r ON r.ID = rl.RETURN_ID AND r.DEL_FLAG = '0' " +
            "LEFT JOIN erp_mdm_item md ON md.ITEM_CODE = rl.ITEM_CODE " +
            "WHERE r.OUT_DATE >= #{monthStart} AND r.OUT_DATE < #{nextMonthStart} AND rl.DEL_FLAG = '0' " +
            "GROUP BY DATE_FORMAT(r.OUT_DATE, '%Y%m'), rl.ITEM_CODE, r.SUPPLIER_ID, " +
            "         COALESCE(md.CATEGORY_CODE, '-') " +
            "ON DUPLICATE KEY UPDATE " +
            "  BATCH_NO = VALUES(BATCH_NO), RETURN_AMT = VALUES(RETURN_AMT)")
    int upsertFromReturn(@Param("batchNo") String batchNo,
                         @Param("monthStart") String monthStart,
                         @Param("nextMonthStart") String nextMonthStart);

    /** 收尾：无发票行的月记暂估中 + 重算差异列（当月） */
    @Update("UPDATE erp_bi_cost_snapshot SET " +
            "  ESTIMATING = CASE WHEN INV_AMT IS NULL AND PO_QTY > 0 THEN 1 ELSE 0 END, " +
            "  DIFF_AMT = CASE WHEN INV_AMT IS NULL THEN NULL ELSE INV_AMT - COALESCE(PO_AMT,0) END " +
            "WHERE MONTH_TAG = #{monthTag} AND DEL_FLAG = '0'")
    int finalizeMonth(@Param("monthTag") String monthTag);
}
