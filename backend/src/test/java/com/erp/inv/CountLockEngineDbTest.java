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
 * 引擎校验链⑤ 仓位盘点锁（真实 MySQL，spec count-management 仓位盘点锁 / stock-posting-engine MODIFIED）：
 * IN/OUT 指定锁仓位 422 无库存流水变动、缺省分配挑序跳过锁仓位（文案细化）、
 * ADJUST 类型豁免、无任务时零拦截。
 */
@SpringBootTest
class CountLockEngineDbTest {

    private static final String WH = "WH-COUNTLK";
    private static final String ITEM = "IT-COUNTLK-01";

    @Autowired
    private StockPostingEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    private String taskId;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_count_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_count_task WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('clk-it-01', ?, '盘点锁测试物料', '0001', 'PCS', "
                + "'G001', 'BUY', 'NORMAL', '1', '0', '1', 'junit')", ITEM);
        // 盘点任务 COUNTING + 锁 BIN-A01
        taskId = "clk-task-1";
        jdbc.update("INSERT INTO erp_inv_count_task (ID, TASK_NO, TASK_TYPE, WAREHOUSE_CODE, "
                + "STATUS, TOTAL_LINES, CREATE_BY) VALUES (?, 'COUNT-TEST-0001', 'FULL', ?, "
                + "'COUNTING', 1, 'junit')", taskId, WH);
        jdbc.update("INSERT INTO erp_inv_count_line (ID, TASK_ID, BIN_CODE, ITEM_CODE, ITEM_NAME, "
                + "BATCH_NO, BOOK_QTY, COUNT_STATUS, CREATE_BY) VALUES (?, ?, 'BIN-A01', ?, "
                + "'盘点锁测试物料', 'CLK-B1', 100, 'PENDING', 'junit')",
                "clk-line-1", taskId, ITEM);
        // 批次台账（ADJUST_IN 联动建档校验需有效期，BR-4.1-08）
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY, DEL_FLAG, "
                + "VER_NO) VALUES ('clk-led-1', 'CLK-B1', ?, '盘点锁测试物料', ?, ?, '0', '1', "
                + "'junit', '0', 0)", ITEM,
                LocalDate.now().minusDays(60).toString(),
                LocalDate.now().plusDays(300).toString());
        // 库存：BIN-A01（锁）50 + BIN-B01（未锁）50，批次同 CLK-B1
        seedStock("clk-stk-a", "BIN-A01", "50");
        seedStock("clk-stk-b", "BIN-B01", "50");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_count_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_count_task WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
    }

    private void seedStock(String id, String bin, String qty) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                + "BATCH_NO, BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, "
                + "CREATE_BY) VALUES (?, ?, ?, '盘点锁测试物料', 'CLK-B1', ?, ?, 0, 0, ?, "
                + "'2026-10-01', 'junit')", id, WH, ITEM, bin, qty, qty);
    }

    private StockPostingEngine.Line line(String bin, String batch, String qty, boolean in) {
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = WH;
        l.itemCode = ITEM;
        l.itemName = "盘点锁测试物料";
        l.batchNo = batch;
        l.binCode = bin;
        l.qty = new BigDecimal(qty);
        return l;
    }

    private Map<String, Object> stock(String bin) {
        return jdbc.queryForMap("SELECT QTY, AVAILABLE_QTY FROM erp_inv_stock "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BIN_CODE = ?", WH, ITEM, bin);
    }

    private int txnCount(String doc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_NO = ?",
                Integer.class, doc);
    }

    /** IN 指定锁仓位 → 422 无变动（校验链⑤ 显式目标仓位） */
    @Test
    void inToLockedBinBlocked() {
        String doc = "CLK-IN-1";
        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("PURCHASE_IN", "GR", doc,
                        List.of(line("BIN-A01", "CLK-B1", "10", true)))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("仓位盘点冻结中"), ex.getMessage());
        assertEquals(0, txnCount(doc), "阻断无流水");
        assertEquals(0, new BigDecimal("50").compareTo(num(stock("BIN-A01").get("QTY"))));
    }

    /** OUT 指定锁仓位 → 422 无变动 */
    @Test
    void outFromLockedBinBlocked() {
        String doc = "CLK-OUT-1";
        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT", doc,
                        List.of(line("BIN-A01", "CLK-B1", "10", false)))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("仓位盘点冻结中"), ex.getMessage());
        assertEquals(0, txnCount(doc));
        assertEquals(0, new BigDecimal("50").compareTo(num(stock("BIN-A01").get("AVAILABLE_QTY"))));
    }

    /** 缺省分配挑序跳过锁仓位 → 只扣未锁位；锁位全占时文案细化 */
    @Test
    void defaultAllocationSkipsLockedBin() {
        // 需求 30：挑序跳过 BIN-A01，仅从 BIN-B01 扣
        String doc = "CLK-OUT-2";
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "SALES_OUT", "SHIPMENT", doc, List.of(line("", "", "30", false))));
        assertEquals(1, res.allocations.size());
        assertEquals("BIN-B01", res.allocations.get(0).binCode, "锁仓位被挑序跳过");
        assertEquals(1, txnCount(doc), "缺省出库有流水");
        assertEquals(0, new BigDecimal("50").compareTo(num(stock("BIN-A01").get("AVAILABLE_QTY"))),
                "锁仓位未动");
        assertEquals(0, new BigDecimal("20").compareTo(num(stock("BIN-B01").get("AVAILABLE_QTY"))));

        // 需求 80 > 未锁可用 20 → 盘点锁细化文案
        String doc2 = "CLK-OUT-3";
        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT", doc2,
                        List.of(line("", "", "80", false)))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("仓位盘点冻结中，可用未锁库存不足"), ex.getMessage());
    }

    /** ADJUST_OUT/IN 豁免锁校验（调整自身必须能过） */
    @Test
    void adjustTypesExempt() {
        String doc = "CLK-ADJ-1";
        // 盘亏出：指定锁仓位直扣 → 应成功
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "ADJUST_OUT", "COUNT_ADJUST", doc, List.of(line("BIN-A01", "CLK-B1", "5", false))));
        assertEquals(1, res.allocations.size(), "ADJUST_OUT 豁免通过");
        assertEquals(1, txnCount(doc), "ADJUST_OUT 有流水");
        assertEquals(0, new BigDecimal("45").compareTo(num(stock("BIN-A01").get("AVAILABLE_QTY"))));

        String doc2 = "CLK-ADJ-2";
        StockPostingEngine.Result res2 = engine.post(StockPostingEngine.Request.of(
                "ADJUST_IN", "COUNT_ADJUST", doc2, List.of(line("BIN-A01", "CLK-B1", "3", true))));
        assertEquals(1, res2.txnNos.size(), "ADJUST_IN 豁免通过（IN 回填 txnNos）");
        assertEquals(0, new BigDecimal("48").compareTo(num(stock("BIN-A01").get("QTY"))));
    }

    /** 无 COUNTING 任务 → 零拦截（回归：锁查询空任务集短路） */
    @Test
    void noCountTaskNoBlock() {
        jdbc.update("DELETE FROM erp_inv_count_task WHERE WAREHOUSE_CODE = ?", WH);
        String doc = "CLK-NOLOCK-1";
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "SALES_OUT", "SHIPMENT", doc, List.of(line("BIN-A01", "CLK-B1", "10", false))));
        assertEquals(1, res.allocations.size(), "无任务时正常出库");
        assertEquals(1, txnCount(doc), "出库有流水");
        assertEquals(0, new BigDecimal("40").compareTo(num(stock("BIN-A01").get("AVAILABLE_QTY"))));
    }

    /** 任务非 COUNTING（DONE 解锁）→ 不拦 */
    @Test
    void doneTaskUnlocks() {
        jdbc.update("UPDATE erp_inv_count_task SET STATUS = 'DONE' WHERE ID = ?", taskId);
        String doc = "CLK-DONE-1";
        StockPostingEngine.Result res = engine.post(StockPostingEngine.Request.of(
                "SALES_OUT", "SHIPMENT", doc, List.of(line("BIN-A01", "CLK-B1", "10", false))));
        assertEquals(1, res.allocations.size(), "DONE 后解锁");
        assertEquals(1, txnCount(doc), "出库有流水");
    }

    private static java.math.BigDecimal num(Object v) {
        return new java.math.BigDecimal(String.valueOf(v));
    }
}
