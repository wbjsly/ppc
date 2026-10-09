package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvSerial;
import com.erp.entity.inv.InvStock;
import com.erp.entity.inv.InvTransaction;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.sd.Reservation;
import com.erp.service.inv.StockPostingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用出入库过账引擎 DB 集成（真实 MySQL，spec stock-posting-engine，任务 3.2~3.5）：
 * IN 建档/效期推算/首插与补货、OUT FIFO+效期分配与拆批、预留排除、qcFirst、负库存、
 * 流水行级余额链、序列判重、类型停用拒。
 */
@SpringBootTest
class StockPostingEngineDbTest {

    private static final String WH = "WH-ENGT";
    private static final String ITEM = "IT-ENG-01";
    private static final String ITEM_SERIAL = "IT-ENG-SN";

    @Autowired
    private StockPostingEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void purgeAndSeed() {
        // 物理自清（软删行占唯一键的教训）
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE LIKE 'IT-ENG%'");
        jdbc.update("DELETE FROM erp_inv_serial WHERE ITEM_CODE LIKE 'IT-ENG%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'IT-ENG%'");
        jdbc.update("DELETE FROM erp_ops_outbox WHERE EVENT_TYPE = 'STOCK.MOVED' AND SOURCE LIKE 'ENG-%'");
        jdbc.update("UPDATE erp_inv_doc_type SET ENABLED = 1");
        // 物料：批次管理（保质期 90 天）与序列管理
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, "
                + "BATCH_FLAG, SERIAL_FLAG, SHELF_LIFE_DAYS, STATUS, CREATE_BY) VALUES "
                + "('it-eng-01', ?, '引擎测试物料', '0001', 'PCS', 'G001', 'BUY', 'NORMAL', "
                + "'1', '0', 90, '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, "
                + "BATCH_FLAG, SERIAL_FLAG, STATUS, CREATE_BY) VALUES "
                + "('it-eng-sn', ?, '序列测试物料', '0001', 'PCS', 'G001', 'BUY', 'NORMAL', "
                + "'0', '1', '1', 'junit')", ITEM_SERIAL);
    }

    @AfterEach
    void cleanup() {
        purgeAndSeed();
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE LIKE 'IT-ENG%'");
        jdbc.update("UPDATE erp_inv_doc_type SET ENABLED = 1");
    }

    // ---------- 帮助 ----------

    private StockPostingEngine.Line inLine(String batch, String qty) {
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = WH;
        l.itemCode = ITEM;
        l.itemName = "引擎测试物料";
        l.batchNo = batch;
        l.qty = new BigDecimal(qty);
        return l;
    }

    private StockPostingEngine.Request inReq(StockPostingEngine.Line... lines) {
        StockPostingEngine.Request r = StockPostingEngine.Request.of(
                "PURCHASE_IN", "GR", "ENG-GR-" + System.nanoTime() % 100000,
                List.of(lines));
        return r;
    }

    private Map<String, Object> stockOf(String batch) {
        return jdbc.queryForMap("SELECT QTY, AVAILABLE_QTY, QC_QTY, FIN_QTY, INBOUND_DATE "
                + "FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ?",
                WH, ITEM, batch);
    }

    private BigDecimal num(Object v) {
        return new BigDecimal(String.valueOf(v));
    }

    private List<Map<String, Object>> txns(String bizDocNo) {
        return jdbc.queryForList("SELECT * FROM erp_inv_transaction WHERE BIZ_DOC_NO = ? "
                + "ORDER BY CREATE_DATE, TXN_NO", bizDocNo);
    }

    // ================= IN =================

    @Test
    void inCreatesLedgerWithInferredExpiryAndTxn() {
        StockPostingEngine.Line line = inLine("B-ENG-NEW", "100");
        StockPostingEngine.Request req = inReq(line);
        StockPostingEngine.Result res = engine.post(req);

        Map<String, Object> row = stockOf("B-ENG-NEW");
        assertEquals(0, new BigDecimal("100").compareTo(num(row.get("QTY"))));
        assertEquals(0, new BigDecimal("100").compareTo(num(row.get("AVAILABLE_QTY"))));
        assertEquals(LocalDate.now().toString(), String.valueOf(row.get("INBOUND_DATE")),
                "首插记当日 INBOUND_DATE");

        // 批次台账联动建档（效期按保质期推算 = 今日 + 90）
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT EXPIRY_DATE, REMARK, SOURCE_DOC_NO FROM erp_inv_batch "
                        + "WHERE ITEM_CODE = ? AND BATCH_NO = ?", ITEM, "B-ENG-NEW");
        assertEquals(LocalDate.now().plusDays(90).toString(), String.valueOf(ledger.get("EXPIRY_DATE")));
        assertTrue(String.valueOf(ledger.get("REMARK")).contains("过账联动"), String.valueOf(ledger.get("REMARK")));
        assertEquals(req.bizDocNo, ledger.get("SOURCE_DOC_NO"));

        // 流水：IN 一条，before 0 after 100
        List<Map<String, Object>> t = txns(req.bizDocNo);
        assertEquals(1, t.size());
        assertEquals("IN", t.get(0).get("DIRECTION"));
        assertEquals("PURCHASE_IN", t.get(0).get("TYPE_CODE"));
        assertEquals(0, new BigDecimal("0").compareTo(num(t.get(0).get("BEFORE_QTY"))));
        assertEquals(0, new BigDecimal("100").compareTo(num(t.get(0).get("AFTER_QTY"))));
        assertTrue(String.valueOf(t.get(0).get("TXN_NO")).matches("TX\\d{6}-\\d{6}"));
        assertEquals(res.txnNos.get(0), t.get(0).get("TXN_NO"));
    }

    @Test
    void inTopUpKeepsInboundDateAndLedgerNotOverwritten() {
        engine.post(inReq(inLine("B-ENG-TOP", "100")));
        jdbc.update("UPDATE erp_inv_stock SET INBOUND_DATE = '2026-10-01' "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ?", WH, ITEM, "B-ENG-TOP");
        // 手工先建了不同效期的同批台账？此处已有引擎建档，再次过账不改写
        engine.post(inReq(inLine("B-ENG-TOP", "50")));

        Map<String, Object> row = stockOf("B-ENG-TOP");
        assertEquals(0, new BigDecimal("150").compareTo(num(row.get("QTY"))));
        assertEquals("2026-10-01", String.valueOf(row.get("INBOUND_DATE")), "补货不刷新");
        int ledgerRows = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_batch "
                + "WHERE ITEM_CODE = ? AND BATCH_NO = ?", Integer.class, ITEM, "B-ENG-TOP");
        assertEquals(1, ledgerRows, "既有批次不重复建档");
    }

    @Test
    void inIntoQcTargetBucket() {
        StockPostingEngine.Line line = inLine("B-ENG-QC", "40");
        line.intoQc = true;
        engine.post(inReq(line));
        Map<String, Object> row = stockOf("B-ENG-QC");
        assertEquals(0, new BigDecimal("40").compareTo(num(row.get("QC_QTY"))));
        assertEquals(0, new BigDecimal("0").compareTo(num(row.get("AVAILABLE_QTY"))));
        assertEquals(0, new BigDecimal("40").compareTo(num(row.get("QTY"))));
    }

    @Test
    void inBatchManagedWithoutShelfLifeRejected() {
        jdbc.update("UPDATE erp_mdm_item SET SHELF_LIFE_DAYS = NULL WHERE ITEM_CODE = ?", ITEM);
        StockPostingEngine.Line line = inLine("B-ENG-NOEXP", "10");
        line.expiryDate = null;
        ServiceException ex = assertThrows(ServiceException.class,
                () -> engine.post(inReq(line)));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("有效期"), ex.getMessage());
    }

    // ================= OUT =================

    /** 缺省分配：同日入库按效期升序（FEFO），拆批两条流水、余额链相接 */
    @Test
    void outAllocatesFifoFefoAndWritesChainedTxns() {
        seedStock("B-A", "100", "2026-10-01", "2027-06-30");
        seedStock("B-B", "100", "2026-10-01", "2026-12-31");   // 同日更早效期 → 先出

        StockPostingEngine.Line line = new StockPostingEngine.Line();
        line.warehouseCode = WH;
        line.itemCode = ITEM;
        line.qty = new BigDecimal("120");
        StockPostingEngine.Request req = StockPostingEngine.Request.of(
                "SALES_OUT", "SHIPMENT", "ENG-SH-" + System.nanoTime() % 100000, List.of(line));
        StockPostingEngine.Result res = engine.post(req);

        // 分配：B-B 100 + B-A 20（同日效期升序）
        assertEquals(2, res.allocations.size());
        assertEquals("B-B", res.allocations.get(0).batchNo);
        assertEquals(0, new BigDecimal("100").compareTo(res.allocations.get(0).qty));
        assertEquals("B-A", res.allocations.get(1).batchNo);
        assertEquals(0, new BigDecimal("20").compareTo(res.allocations.get(1).qty));

        assertEquals(0, new BigDecimal("80").compareTo(num(stockOf("B-A").get("QTY"))));
        assertEquals(0, num(stockOf("B-B").get("QTY")).compareTo(BigDecimal.ZERO), "B-B 出完为 0");

        // 流水两条 + 余额链
        List<Map<String, Object>> t = txns(req.bizDocNo);
        assertEquals(2, t.size());
        assertEquals("OUT", t.get(0).get("DIRECTION"));
        assertEquals(0, new BigDecimal("0").compareTo(num(t.get(0).get("AFTER_QTY"))), "B-B 出完归 0");
        assertEquals(0, new BigDecimal("80").compareTo(num(t.get(1).get("AFTER_QTY"))));
        // 双列恒等
        Map<String, Object> a = stockOf("B-A");
        assertEquals(0, num(a.get("QTY")).compareTo(
                num(a.get("AVAILABLE_QTY")).add(num(a.get("QC_QTY"))).add(num(a.get("FIN_QTY")))));
    }

    /** 预留不参与分配（分配可用 = AVAILABLE − ACTIVE 预留，design D3） */
    @Test
    void outExcludesReservedFromBasis() {
        seedStock("B-RES", "100", "2026-10-02", null);
        jdbc.update("INSERT INTO erp_sd_reservation (ID, SO_ID, SO_NO, LINE_ID, LINE_NO, "
                + "ITEM_CODE, WAREHOUSE_CODE, BATCH_NO, QTY, STATUS, LOCK_AT) VALUES "
                + "('eng-res-1', 'SO-ENG', 'SO-ENG-1', 'SL-ENG-1', 1, ?, ?, 'B-RES', 30, 'ACTIVE', NOW())",
                ITEM, WH);

        ServiceException ex = assertThrows(ServiceException.class, () -> {
            StockPostingEngine.Line line = new StockPostingEngine.Line();
            line.warehouseCode = WH;
            line.itemCode = ITEM;
            line.qty = new BigDecimal("90");
            engine.post(StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT",
                    "ENG-SH-RES", List.of(line)));
        });
        assertTrue(ex.getMessage().contains("库存不足，当前可用量为 70"), ex.getMessage());
    }

    /** 负库存硬阻断（C-4.4-01 文案） */
    @Test
    void outNegativeBlocked() {
        seedStock("B-NEG", "20", "2026-10-03", null);
        ServiceException ex = assertThrows(ServiceException.class, () -> {
            StockPostingEngine.Line line = new StockPostingEngine.Line();
            line.warehouseCode = WH;
            line.itemCode = ITEM;
            line.qty = new BigDecimal("30");
            engine.post(StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT",
                    "ENG-SH-NEG", List.of(line)));
        });
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("库存不足，当前可用量为 20"), ex.getMessage());
    }

    /** 指定批次 + qcFirst（退货核销锁定量优先） */
    @Test
    void outSpecifiedBatchWithQcFirst() {
        // qc20 + avail100
        seedStock("B-QC", "120", "2026-10-04", null);
        jdbc.update("UPDATE erp_inv_stock SET QC_QTY = 20, AVAILABLE_QTY = 100 "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ?", WH, ITEM, "B-QC");

        StockPostingEngine.Line line = new StockPostingEngine.Line();
        line.warehouseCode = WH;
        line.itemCode = ITEM;
        line.batchNo = "B-QC";
        line.batchSpecified = true;
        line.qcFirst = true;
        line.qty = new BigDecimal("30");
        StockPostingEngine.Request req = StockPostingEngine.Request.of(
                "QUALITY_RETURN_OUT", "RETURN", "ENG-RT-" + System.nanoTime() % 100000,
                List.of(line));
        engine.post(req);

        Map<String, Object> row = stockOf("B-QC");
        assertEquals(0, new BigDecimal("0").compareTo(num(row.get("QC_QTY"))), "QC 20 全核销");
        assertEquals(0, new BigDecimal("90").compareTo(num(row.get("AVAILABLE_QTY"))), "余 10 扣可用");
        assertEquals(0, new BigDecimal("90").compareTo(num(row.get("QTY"))));
        assertEquals(1, txns(req.bizDocNo).size());
    }

    // ================= 序列判重 =================

    @Test
    void serialChecksInAndOut() {
        jdbc.update("INSERT INTO erp_inv_serial (ID, SERIAL_NO, ITEM_CODE, STATUS, CREATE_BY) "
                + "VALUES ('eng-sn-1', 'ENG-SN-001', ?, 'IN_STOCK', 'junit')", ITEM_SERIAL);

        // 入库重复 → 422（BR-4.11-15）
        StockPostingEngine.Line in = new StockPostingEngine.Line();
        in.warehouseCode = WH;
        in.itemCode = ITEM_SERIAL;
        in.itemName = "序列测试物料";
        in.batchNo = "";
        in.qty = new BigDecimal("1");
        in.serials = List.of("ENG-SN-001");
        ServiceException dup = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("PURCHASE_IN", "GR", "ENG-GR-SN1", List.of(in))));
        assertTrue(dup.getMessage().contains("序列号重复"), dup.getMessage());

        // 序列管理物料缺序列 → 422（C-4.4-02）
        StockPostingEngine.Line noSerial = new StockPostingEngine.Line();
        noSerial.warehouseCode = WH;
        noSerial.itemCode = ITEM_SERIAL;
        noSerial.batchNo = "";
        noSerial.qty = new BigDecimal("1");
        ServiceException missing = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("PURCHASE_IN", "GR", "ENG-GR-SN2", List.of(noSerial))));
        assertTrue(missing.getMessage().contains("序列号"), missing.getMessage());

        // 出库序列不可用（不在库）→ 422
        StockPostingEngine.Line out = new StockPostingEngine.Line();
        out.warehouseCode = WH;
        out.itemCode = ITEM_SERIAL;
        out.qty = new BigDecimal("1");
        out.serials = List.of("ENG-SN-NEVER");
        ServiceException unusable = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT", "ENG-SH-SN", List.of(out))));
        assertTrue(unusable.getMessage().contains("不可用"), unusable.getMessage());
    }

    // ================= 类型开关 / 分配 dry-run =================

    @Test
    void disabledTypeRejected() {
        jdbc.update("UPDATE erp_inv_doc_type SET ENABLED = 0 WHERE TYPE_CODE = 'MATERIAL_OUT'");
        StockPostingEngine.Line line = inLine("B-ENG-DIS", "1");
        line.itemCode = ITEM;
        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("MATERIAL_OUT", "MI", "ENG-MI-1", List.of(line))));
        assertTrue(ex.getMessage().contains("停用"), ex.getMessage());
    }

    @Test
    void allocateDryRunSortsAndGaps() {
        seedStock("B-ALLOC-1", "50", "2026-10-01", "2027-06-30");
        seedStock("B-ALLOC-2", "50", "2026-10-01", "2026-12-31");
        List<StockPostingEngine.Alloc> allocs = engine.allocate(WH, ITEM, new BigDecimal("60"));
        assertEquals("B-ALLOC-2", allocs.get(0).batchNo, "同日效期升序");
        assertEquals(0, new BigDecimal("50").compareTo(allocs.get(0).qty));
        assertEquals("B-ALLOC-1", allocs.get(1).batchNo);
        assertEquals(0, new BigDecimal("10").compareTo(allocs.get(1).qty));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> engine.allocate(WH, ITEM, new BigDecimal("200")));
        assertTrue(ex.getMessage().contains("可用量不足"), ex.getMessage());
        assertTrue(ex.getMessage().contains("缺口"), ex.getMessage());
    }

    /** 六类型流水贯通：MATERIAL_OUT / OTHER_RETURN_OUT / SALES_RETURN_IN / VMI_TRANSFER_IN */
    @Test
    void remainingTypeCodesWriteFlow() {
        // OUT：MATERIAL_OUT 指定批次
        seedStock("B-TYPE-OUT", "50", "2026-10-05", null);
        StockPostingEngine.Line out1 = new StockPostingEngine.Line();
        out1.warehouseCode = WH;
        out1.itemCode = ITEM;
        out1.batchNo = "B-TYPE-OUT";
        out1.batchSpecified = true;
        out1.qty = new BigDecimal("20");
        var r1 = StockPostingEngine.Request.of("MATERIAL_OUT", "MATERIAL_ISSUE",
                "ENG-MI-1", List.of(out1));
        engine.post(r1);
        var t1 = txns("ENG-MI-1");
        assertEquals(1, t1.size());
        assertEquals("OUT", t1.get(0).get("DIRECTION"));
        assertEquals("MATERIAL_OUT", t1.get(0).get("TYPE_CODE"));
        assertEquals(0, new BigDecimal("50").compareTo(num(t1.get(0).get("BEFORE_QTY"))));
        assertEquals(0, new BigDecimal("30").compareTo(num(t1.get(0).get("AFTER_QTY"))));

        // OUT：OTHER_RETURN_OUT（qcFirst 指定批次，QC 无量 → 全扣可用）
        StockPostingEngine.Line out2 = new StockPostingEngine.Line();
        out2.warehouseCode = WH;
        out2.itemCode = ITEM;
        out2.batchNo = "B-TYPE-OUT";
        out2.batchSpecified = true;
        out2.qcFirst = true;
        out2.qty = new BigDecimal("10");
        engine.post(StockPostingEngine.Request.of("OTHER_RETURN_OUT", "RETURN",
                "ENG-ORT-1", List.of(out2)));
        var t2 = txns("ENG-ORT-1");
        assertEquals(1, t2.size());
        assertEquals("OTHER_RETURN_OUT", t2.get(0).get("TYPE_CODE"));
        assertEquals(0, new BigDecimal("30").compareTo(num(t2.get(0).get("BEFORE_QTY"))));
        assertEquals(0, new BigDecimal("20").compareTo(num(t2.get(0).get("AFTER_QTY"))));

        // IN：SALES_RETURN_IN
        StockPostingEngine.Line in1 = new StockPostingEngine.Line();
        in1.warehouseCode = WH;
        in1.itemCode = ITEM;
        in1.batchNo = "B-TYPE-SRI";
        in1.qty = new BigDecimal("15");
        engine.post(StockPostingEngine.Request.of("SALES_RETURN_IN", "RETURN",
                "ENG-SRI-1", List.of(in1)));
        var t3 = txns("ENG-SRI-1");
        assertEquals(1, t3.size());
        assertEquals("IN", t3.get(0).get("DIRECTION"));
        assertEquals("SALES_RETURN_IN", t3.get(0).get("TYPE_CODE"));
        assertEquals(0, new BigDecimal("15").compareTo(num(t3.get(0).get("AFTER_QTY"))));

        // IN：VMI_TRANSFER_IN（物权转移入自有）
        StockPostingEngine.Line in2 = new StockPostingEngine.Line();
        in2.warehouseCode = WH;
        in2.itemCode = ITEM;
        in2.batchNo = "B-TYPE-VMI";
        in2.qty = new BigDecimal("25");
        engine.post(StockPostingEngine.Request.of("VMI_TRANSFER_IN", "MATERIAL_ISSUE",
                "ENG-VMI-1", List.of(in2)));
        var t4 = txns("ENG-VMI-1");
        assertEquals(1, t4.size());
        assertEquals("IN", t4.get(0).get("DIRECTION"));
        assertEquals("VMI_TRANSFER_IN", t4.get(0).get("TYPE_CODE"));

        // 冻结不产生流水（数量平移归冻结台账）
        int before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?",
                Integer.class, WH);
        jdbc.update("UPDATE erp_inv_stock SET AVAILABLE_QTY = AVAILABLE_QTY - 5, "
                + "QC_QTY = QC_QTY + 5 WHERE WAREHOUSE_CODE = ? AND BATCH_NO = 'B-TYPE-OUT'", WH);
        int after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?",
                Integer.class, WH);
        assertEquals(before, after, "冻结平移不得写流水");
    }

    // ================= 位级（change add-bin-assignment，任务 2.1~2.3） =================

    /** IN 指定仓位建独立位行；同批次跨位并存；补货不刷新各行 INBOUND_DATE；流水带 BIN_CODE */
    @Test
    void inBinRowsDistinctAndTxnCarriesBin() {
        StockPostingEngine.Line l1 = inLine("B-BIN-1", "100");
        l1.binCode = "A-01-01-01";
        engine.post(inReq(l1));
        StockPostingEngine.Line l2 = inLine("B-BIN-1", "50");
        l2.binCode = "C-01-01-01";
        engine.post(inReq(l2));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT BIN_CODE, QTY, INBOUND_DATE FROM erp_inv_stock "
                        + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ? "
                        + "ORDER BY BIN_CODE", WH, ITEM, "B-BIN-1");
        assertEquals(2, rows.size(), "同批次跨仓位两条位行");
        assertEquals("A-01-01-01", rows.get(0).get("BIN_CODE"));
        assertEquals(0, new BigDecimal("100").compareTo(num(rows.get(0).get("QTY"))));
        assertEquals("C-01-01-01", rows.get(1).get("BIN_CODE"));
        assertEquals(0, new BigDecimal("50").compareTo(num(rows.get(1).get("QTY"))));

        // A 位补货不刷新 INBOUND_DATE
        jdbc.update("UPDATE erp_inv_stock SET INBOUND_DATE = '2026-10-01' "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ? AND BIN_CODE = ?",
                WH, ITEM, "B-BIN-1", "A-01-01-01");
        StockPostingEngine.Line l3 = inLine("B-BIN-1", "10");
        l3.binCode = "A-01-01-01";
        engine.post(inReq(l3));
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT QTY, INBOUND_DATE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? "
                        + "AND ITEM_CODE = ? AND BATCH_NO = ? AND BIN_CODE = ?",
                WH, ITEM, "B-BIN-1", "A-01-01-01");
        assertEquals(0, new BigDecimal("110").compareTo(num(a.get("QTY"))));
        assertEquals("2026-10-01", String.valueOf(a.get("INBOUND_DATE")), "补货不刷新");

        // 流水带 BIN_CODE 与位级 before/after
        List<Map<String, Object>> t = jdbc.queryForList(
                "SELECT BIN_CODE, BEFORE_QTY, AFTER_QTY FROM erp_inv_transaction "
                        + "WHERE ITEM_CODE = ? AND BATCH_NO = ? ORDER BY CREATE_DATE, TXN_NO",
                ITEM, "B-BIN-1");
        assertEquals(3, t.size());
        assertEquals("A-01-01-01", t.get(0).get("BIN_CODE"));
        assertEquals("C-01-01-01", t.get(1).get("BIN_CODE"));
        assertEquals(0, new BigDecimal("50").compareTo(num(t.get(1).get("AFTER_QTY"))));
        assertEquals(0, new BigDecimal("110").compareTo(num(t.get(2).get("AFTER_QTY"))), "补货后位级余额");
    }

    /** IN 不带仓位 → 落未分配虚拟位 ''（队列B 补上架数据源） */
    @Test
    void inWithoutBinLandsUnassigned() {
        StockPostingEngine.Line line = inLine("B-BIN-NA", "30");
        engine.post(inReq(line));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT BIN_CODE, QTY FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? "
                        + "AND ITEM_CODE = ? AND BATCH_NO = ?", WH, ITEM, "B-BIN-NA");
        assertEquals("", row.get("BIN_CODE"), "空仓位落未分配位");
        assertEquals(0, new BigDecimal("30").compareTo(num(row.get("QTY"))));
    }

    /** 指定批次跨位扣减：按位行 FIFO 逐行拆（A 30 + C 20），流水位级余额链 */
    @Test
    void outSpecifiedBatchSplitsAcrossBins() {
        seedStockBin("B-BIN-S", "A-01-01-01", "30", "2026-10-01");
        seedStockBin("B-BIN-S", "C-01-01-01", "40", "2026-10-05");

        StockPostingEngine.Line line = new StockPostingEngine.Line();
        line.warehouseCode = WH;
        line.itemCode = ITEM;
        line.batchNo = "B-BIN-S";
        line.batchSpecified = true;
        line.qty = new BigDecimal("50");
        StockPostingEngine.Request req = StockPostingEngine.Request.of(
                "SALES_OUT", "SHIPMENT", "ENG-SH-BIN-" + System.nanoTime() % 10000,
                List.of(line));
        StockPostingEngine.Result res = engine.post(req);

        assertEquals(2, res.allocations.size(), "跨位拆两条分配");
        assertEquals("A-01-01-01", res.allocations.get(0).binCode);
        assertEquals(0, new BigDecimal("30").compareTo(res.allocations.get(0).qty));
        assertEquals("C-01-01-01", res.allocations.get(1).binCode);
        assertEquals(0, new BigDecimal("20").compareTo(res.allocations.get(1).qty));

        assertEquals(0, num(binStock("B-BIN-S", "A-01-01-01").get("QTY")).compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("20").compareTo(num(binStock("B-BIN-S", "C-01-01-01").get("QTY"))));

        List<Map<String, Object>> t = txns(req.bizDocNo);
        assertEquals(2, t.size(), "位行级流水一维一条");
        assertEquals("A-01-01-01", t.get(0).get("BIN_CODE"));
        assertEquals(0, new BigDecimal("30").compareTo(num(t.get(0).get("QTY"))), "QTY=本条变动量");
        assertEquals(0, new BigDecimal("30").compareTo(num(t.get(0).get("BEFORE_QTY"))));
        assertEquals(0, new BigDecimal("0").compareTo(num(t.get(0).get("AFTER_QTY"))));
        assertEquals("C-01-01-01", t.get(1).get("BIN_CODE"));
        assertEquals(0, new BigDecimal("40").compareTo(num(t.get(1).get("BEFORE_QTY"))));
        assertEquals(0, new BigDecimal("20").compareTo(num(t.get(1).get("AFTER_QTY"))));
    }

    /** 缺省分配按位行序：同批次先出早入库的仓位；allocate dry-run 带 binCode */
    @Test
    void outDefaultAllocatesByRowOrderAndAllocCarriesBin() {
        seedStockBin("B-BIN-D", "C-01-01-01", "40", "2026-10-05");
        seedStockBin("B-BIN-D", "A-01-01-01", "30", "2026-10-01");

        // dry-run 先行：同序且带 binCode
        List<StockPostingEngine.Alloc> dry = engine.allocate(WH, ITEM, new BigDecimal("50"));
        assertEquals(2, dry.size());
        assertEquals("A-01-01-01", dry.get(0).binCode, "早入库仓位先分配");
        assertEquals("C-01-01-01", dry.get(1).binCode);

        StockPostingEngine.Line line = new StockPostingEngine.Line();
        line.warehouseCode = WH;
        line.itemCode = ITEM;
        line.qty = new BigDecimal("50");
        StockPostingEngine.Request req = StockPostingEngine.Request.of(
                "SALES_OUT", "SHIPMENT", "ENG-SH-D.bin-" + System.nanoTime() % 10000,
                List.of(line));
        StockPostingEngine.Result res = engine.post(req);

        assertEquals(2, res.allocations.size());
        assertEquals("A-01-01-01", res.allocations.get(0).binCode, "早入库仓位先出");
        assertEquals(0, new BigDecimal("30").compareTo(res.allocations.get(0).qty));
        assertEquals("C-01-01-01", res.allocations.get(1).binCode);
        assertEquals(0, new BigDecimal("20").compareTo(res.allocations.get(1).qty));
    }

    /** 批次级预留跨位只扣一次（预算封顶，不按行重复减） */
    @Test
    void reservedAppliesOnceAcrossBinRows() {
        seedStockBin("B-BIN-RES", "A-01-01-01", "50", "2026-10-01");
        seedStockBin("B-BIN-RES", "C-01-01-01", "50", "2026-10-02");
        jdbc.update("INSERT INTO erp_sd_reservation (ID, SO_ID, SO_NO, LINE_ID, LINE_NO, "
                + "ITEM_CODE, WAREHOUSE_CODE, BATCH_NO, QTY, STATUS, LOCK_AT) VALUES "
                + "('eng-res-bin', 'SO-ENG2', 'SO-ENG2-1', 'SL-ENG2-1', 1, ?, ?, 'B-BIN-RES', "
                + "30, 'ACTIVE', NOW())", ITEM, WH);

        // 可用 = 100 − 30 = 70：出 70 成功、出 71 阻断
        StockPostingEngine.Line ok = new StockPostingEngine.Line();
        ok.warehouseCode = WH;
        ok.itemCode = ITEM;
        ok.batchNo = "B-BIN-RES";
        ok.batchSpecified = true;
        ok.qty = new BigDecimal("70");
        engine.post(StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT",
                "ENG-SH-RES2", List.of(ok)));

        StockPostingEngine.Line over = new StockPostingEngine.Line();
        over.warehouseCode = WH;
        over.itemCode = ITEM;
        over.batchNo = "B-BIN-RES";
        over.batchSpecified = true;
        over.qty = new BigDecimal("71");
        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("SALES_OUT", "SHIPMENT",
                        "ENG-SH-RES3", List.of(over))));
        assertEquals(422, ex.getCode());
    }

    // ---------- fixture ----------

    private void seedStock(String batch, String qty, String inbound, String expiry) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "(?, ?, ?, '引擎测试物料', ?, ?, 0, 0, ?, ?, 'junit')",
                "eng-stk-" + batch, WH, ITEM, batch, qty, qty, inbound);
        if (expiry != null) {
            jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, EXPIRY_DATE, "
                    + "STATUS, CREATE_BY) VALUES (?, ?, ?, '引擎测试物料', ?, '1', 'junit')",
                    "eng-led-" + batch, batch, ITEM, expiry);
        }
    }

    /** 位行夹具（同批次多仓位用唯一 ID 区分，change add-bin-assignment） */
    private void seedStockBin(String batch, String bin, String qty, String inbound) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "(?, ?, ?, '引擎测试物料', ?, ?, ?, 0, 0, ?, ?, 'junit')",
                "eng-stk-" + batch + "-" + bin, WH, ITEM, batch, bin, qty, qty, inbound);
    }

    private Map<String, Object> binStock(String batch, String bin) {
        return jdbc.queryForMap("SELECT QTY, AVAILABLE_QTY, QC_QTY FROM erp_inv_stock "
                        + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ? AND BIN_CODE = ?",
                WH, ITEM, batch, bin);
    }
}
