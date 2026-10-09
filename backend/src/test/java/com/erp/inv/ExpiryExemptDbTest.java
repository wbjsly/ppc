package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.service.inv.StockPostingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎④ 豁免口径 DB 侧（spec expiry-management 需求⑤，design D3，任务 2.3）：
 * 豁免期内出库通过（flag 未刷也放行）、豁免过期即拦（flag 未刷也拦，关 D2 的 24h 窗口）、
 * 质量冻结不受豁免影响仍拦。
 */
@SpringBootTest
class ExpiryExemptDbTest {

    private static final String WH = "WH-EXEMPT";
    private static final String ITEM = "IT-EXEMPT-01";

    @Autowired
    private StockPostingEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void purgeAndSeed() {
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, "
                + "BATCH_FLAG, SERIAL_FLAG, SHELF_LIFE_DAYS, STATUS, CREATE_BY) VALUES "
                + "('it-exempt-01', ?, '豁免口径测试物料', '0001', 'PCS', 'G001', 'BUY', 'NORMAL', "
                + "'1', '0', 365, '1', 'junit')", ITEM);
    }

    @AfterEach
    void cleanup() {
        purgeAndSeed();
    }

    /**
     * seed 到线批次：总 100 天（生产 60 天前、效期 40 天后 < 阈值 50 → 实时到线）。
     * qc>0 模拟质量冻结叠加场景（QC_QTY 不计可用）。
     */
    private void seed(String batch, String avail, String qc, String lockFlag, LocalDate exemptUntil) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                        + "(?, ?, ?, '豁免口径测试物料', ?, ?, ?, 0, ?, '2026-10-01', 'junit')",
                "exm-stk-" + batch, WH, ITEM, batch,
                new BigDecimal(avail).add(new BigDecimal(qc)).toString(), qc, avail);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                        + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, EVAL_EXEMPT_UNTIL, "
                        + "STATUS, CREATE_BY) VALUES (?, ?, ?, '豁免口径测试物料', ?, ?, ?, ?, '1', 'junit')",
                "exm-led-" + batch, batch, ITEM,
                LocalDate.now().minusDays(60).toString(), LocalDate.now().plusDays(40).toString(),
                lockFlag, exemptUntil == null ? null : exemptUntil.toString());
    }

    private StockPostingEngine.Line outLine(String batch, String qty) {
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = WH;
        l.itemCode = ITEM;
        l.itemName = "豁免口径测试物料";
        l.batchNo = batch;
        l.binCode = "";
        l.qty = new BigDecimal(qty);
        return l;
    }

    private Map<String, Object> stockOf(String batch) {
        return jdbc.queryForMap("SELECT QTY, AVAILABLE_QTY FROM erp_inv_stock "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ?", WH, ITEM, batch);
    }

    private static java.math.BigDecimal num(Object v) {
        return new java.math.BigDecimal(String.valueOf(v));
    }

    /** 豁免期内：flag=1（扫描未刷）也放行出库 */
    @Test
    void validExemptionAllowsPosting() {
        seed("B-EXEMPT", "100", "0", "1", LocalDate.now().plusDays(10));
        String doc = "EXEMPT-1-" + System.nanoTime() % 100000;

        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "SALES_OUT", "SHIPMENT", doc, List.of(outLine("B-EXEMPT", "30"))));

        assertEquals(1, res.allocations.size(), "豁免期出库通过");
        assertEquals(0, new BigDecimal("70").compareTo(num(stockOf("B-EXEMPT").get("AVAILABLE_QTY"))));
    }

    /** 豁免过期：flag=0（扫描未刷）也即拦——实时到线判定关闭 24h 窗口 */
    @Test
    void expiredExemptionBlocksImmediately() {
        seed("B-EXPIRED", "100", "0", "0", LocalDate.now().minusDays(1));
        String doc = "EXEMPT-2-" + System.nanoTime() % 100000;

        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT", doc,
                        List.of(outLine("B-EXPIRED", "30")))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("效期锁定"), ex.getMessage());
        assertEquals(0, new BigDecimal("100").compareTo(num(stockOf("B-EXPIRED").get("AVAILABLE_QTY"))));
    }

    /** 质量冻结（QC_QTY）不受豁免影响：可用为 0 → 出库仍失败 */
    @Test
    void exemptionDoesNotBypassQualityFreeze() {
        seed("B-QC", "0", "100", "1", LocalDate.now().plusDays(10));
        String doc = "EXEMPT-3-" + System.nanoTime() % 100000;

        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT", doc,
                        List.of(outLine("B-QC", "10")))));
        assertEquals(422, ex.getCode(), "冻结叠加仍拦截");
    }
}
