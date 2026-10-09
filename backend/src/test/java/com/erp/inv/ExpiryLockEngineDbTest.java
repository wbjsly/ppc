package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvStock;
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
 * 引擎校验链第 ④ 条（真实 MySQL，spec stock-posting-engine MODIFIED / outbound-strategy，任务 2.4）：
 * 指定批次效期锁定 → 422 无库存无流水；缺省分配剔除锁定批次取次批；
 * 同事务中锁定批次不被任何分配触碰。
 */
@SpringBootTest
class ExpiryLockEngineDbTest {

    private static final String WH = "WH-EXLOCK";
    private static final String ITEM = "IT-EXLOCK-01";

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
        jdbc.update("DELETE FROM erp_ops_outbox WHERE SOURCE LIKE 'EXLOCK-%'");
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, "
                + "BATCH_FLAG, SERIAL_FLAG, SHELF_LIFE_DAYS, STATUS, CREATE_BY) VALUES "
                + "('it-exlock-01', ?, '效期锁定引擎物料', '0001', 'PCS', 'G001', 'BUY', 'NORMAL', "
                + "'1', '0', 365, '1', 'junit')", ITEM);
    }

    @AfterEach
    void cleanup() {
        purgeAndSeed();
    }

    private void seed(String batch, String qty, String inbound, String lockFlag) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                        + "(?, ?, ?, '效期锁定引擎物料', ?, ?, 0, 0, ?, ?, 'junit')",
                "exlk-stk-" + batch, WH, ITEM, batch, qty, qty, inbound);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, EXPIRY_DATE, "
                        + "EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES (?, ?, ?, '效期锁定引擎物料', ?, ?, '1', 'junit')",
                "exlk-led-" + batch, batch, ITEM,
                LocalDate.now().plusDays(300).toString(), lockFlag);
    }

    private StockPostingEngine.Line outLine(String batch, String bin, String qty) {
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = WH;
        l.itemCode = ITEM;
        l.itemName = "效期锁定引擎物料";
        l.batchNo = batch;
        l.binCode = bin;
        l.qty = new BigDecimal(qty);
        return l;
    }

    private Map<String, Object> stockOf(String batch) {
        return jdbc.queryForMap("SELECT QTY, AVAILABLE_QTY FROM erp_inv_stock "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ?", WH, ITEM, batch);
    }

    private int txnCount(String docNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_NO = ?",
                Integer.class, docNo);
    }

    /** 指定锁定批次出库 → 422，库存不动、无流水（spec 校验链 ④ 场景） */
    @Test
    void specifiedLockedBatchBlocked() {
        seed("B-LOCK", "100", "2026-10-01", "1");
        String doc = "EXLOCK-1-" + System.nanoTime() % 100000;

        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT", doc,
                        List.of(outLine("B-LOCK", "", "30")))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("效期锁定"), ex.getMessage());

        assertEquals(0, new BigDecimal("100").compareTo(num(stockOf("B-LOCK").get("QTY"))));
        assertEquals(0, txnCount(doc), "阻断无流水");
    }

    /** 缺省分配剔除锁定批次，取下一个未锁定批次（BR-4.4-20 剔池） */
    @Test
    void defaultAllocationSkipsLockedBatch() {
        seed("B-OLD-LOCK", "100", "2026-10-01", "1");   // 最早入库但锁定
        seed("B-OK", "100", "2026-10-05", "0");
        String doc = "EXLOCK-2-" + System.nanoTime() % 100000;

        StockPostingEngine.Line line = new StockPostingEngine.Line();
        line.warehouseCode = WH;
        line.itemCode = ITEM;
        line.qty = new BigDecimal("40");
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "SALES_OUT", "SHIPMENT", doc, List.of(line)));

        assertEquals(1, res.allocations.size());
        assertEquals("B-OK", res.allocations.get(0).batchNo, "锁定批次被剔除");
        // 锁定批次分毫未动
        assertEquals(0, new BigDecimal("100").compareTo(num(stockOf("B-OLD-LOCK").get("QTY"))));
        assertEquals(0, new BigDecimal("60").compareTo(num(stockOf("B-OK").get("QTY"))));
    }

    /** 全部批次锁定且无缺省可分配 → 422 库存不足（而非静默跳过） */
    @Test
    void allLockedGivesInsufficient() {
        seed("B-ONLY", "100", "2026-10-01", "1");
        String doc = "EXLOCK-3-" + System.nanoTime() % 100000;

        StockPostingEngine.Line line = new StockPostingEngine.Line();
        line.warehouseCode = WH;
        line.itemCode = ITEM;
        line.qty = new BigDecimal("30");
        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT", doc, List.of(line))));
        assertEquals(422, ex.getCode());
        assertEquals(0, new BigDecimal("100").compareTo(num(stockOf("B-ONLY").get("QTY"))));
        assertEquals(0, txnCount(doc));
    }

    /** 回位后（标记清除）恢复可出：扫描回位自愈的过账侧对应 */
    @Test
    void unlockedAgainAllowed() {
        seed("B-HEAL", "100", "2026-10-01", "1");
        jdbc.update("UPDATE erp_inv_batch SET EXPIRY_LOCK_FLAG = '0' "
                + "WHERE ITEM_CODE = ? AND BATCH_NO = ?", ITEM, "B-HEAL");
        String doc = "EXLOCK-4-" + System.nanoTime() % 100000;

        engine.post(StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT", doc,
                List.of(outLine("B-HEAL", "", "30"))));

        assertEquals(0, new BigDecimal("70").compareTo(num(stockOf("B-HEAL").get("QTY"))));
        assertEquals(1, txnCount(doc));
    }

    /** 配批分配器（allocate）同样剔除锁定批次（冒烟发现的缺口回归：领料配批不得配出锁定批次） */
    @Test
    void allocateSkipsLockedBatch() {
        seed("B-OLD-LOCK2", "100", "2026-10-01", "1");   // 最早入库但锁定
        seed("B-OK2", "100", "2026-10-05", "0");

        List<StockPostingEngine.Alloc> allocs = engine.allocate(WH, ITEM, new BigDecimal("40"));

        assertEquals(1, allocs.size());
        assertEquals("B-OK2", allocs.get(0).batchNo, "锁定批次被剔除，配批取次批");
        assertEquals(0, new BigDecimal("100").compareTo(num(stockOf("B-OLD-LOCK2").get("QTY"))));
    }

    /** 全部批次锁定时 allocate 报缺口 422，可用口径不含锁定批次 */
    @Test
    void allocateAllLockedGivesShortage() {
        seed("B-ONLY2", "100", "2026-10-01", "1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> engine.allocate(WH, ITEM, new BigDecimal("30")));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("缺口"), ex.getMessage());
    }

    private static BigDecimal num(Object v) {
        return new BigDecimal(String.valueOf(v));
    }
}
