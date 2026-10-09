package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.service.inv.PickRecommendService;
import com.erp.service.inv.StockPostingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拣货推荐确认回写 + 指定仓位过账 DB 语义（真实 MySQL，spec outbound-strategy /
 * stock-posting-engine MODIFIED，任务 6.2/6.3/6.4）：
 * 确认回写批次与仓位且预留表零新增、改写行落偏离台账、已过账单据 422；
 * 行带 bin 过账扣目标位行不动其他位、指定位不足 422 不跨位补。
 */
@SpringBootTest
class PickRecommendDbTest {

    // 领料域固定 DEFAULT_WH 推荐/过账（MaterialIssueServiceImpl 口径）→ 夹具对齐
    private static final String WH = com.erp.entity.inv.InvStock.DEFAULT_WH;
    private static final String ITEM = "IT-PICK-01";
    private static final String ISSUE_NO = "MI-PICK-" + (System.nanoTime() % 100000);

    @Autowired
    private PickRecommendService service;
    @Autowired
    private com.erp.service.inv.BatchRecommendService recommendService;
    @Autowired
    private StockPostingEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                        + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                        + "STATUS, CREATE_BY) VALUES ('pick-it-1', ?, '拣货推荐物料', '0001', 'PC', "
                        + "'STRUCT', 'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        // 批次 B1：两仓位 BIN-01=40、BIN-02=60（入库 10-01）；批次 B2=100（10-05）
        seed("B1", "BIN-01", "40", "2026-10-01");
        seed("B1", "BIN-02", "60", "2026-10-01");
        seed("B2", "", "100", "2026-10-05");
        // DRAFT 领料单（队列 A）
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'OWN', 'WO-PICK', "
                        + "'DRAFT', 'junit', '0', 0)", "pick-mi-1", ISSUE_NO);
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                        + "ITEM_NAME, BATCH_NO, QTY, STOCK_TYPE, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('pick-mi-l1', 'pick-mi-1', 1, ?, ?, '', 50, 'OWN', 'junit', '0', 0)",
                ITEM, "拣货推荐物料");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pick_task_line WHERE TASK_ID IN "
                + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO = ?)", ISSUE_NO);
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO = ?", ISSUE_NO);
        jdbc.update("DELETE FROM erp_inv_batch_deviation WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_NO = ?", ISSUE_NO);
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ITEM_CODE = ?", ITEM);
        // 按物料清（WH-MAIN 是主仓，按仓库清会误删其他夹具/冒烟库存）
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_ops_outbox WHERE SOURCE LIKE 'PICK-%'");
    }

    private void seed(String batch, String bin, String qty, String inbound) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) "
                        + "VALUES (?, ?, ?, '拣货推荐物料', ?, ?, ?, 0, 0, ?, ?, 'junit')",
                "pick-st-" + batch + "-" + (bin.isEmpty() ? "x" : bin), WH, ITEM, batch, bin,
                qty, qty, inbound);
        int exists = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_batch "
                + "WHERE ITEM_CODE = ? AND BATCH_NO = ?", Integer.class, ITEM, batch);
        if (exists == 0) {
            jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                            + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) "
                            + "VALUES (?, ?, ?, '拣货推荐物料', '2026-01-01', '2027-12-31', '0', '1', 'junit')",
                    "pick-lg-" + batch, batch, ITEM);
        }
    }

    private Map<String, Object> binStock(String batch, String bin) {
        return jdbc.queryForMap("SELECT QTY, AVAILABLE_QTY FROM erp_inv_stock "
                        + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BATCH_NO = ? AND BIN_CODE = ?",
                WH, ITEM, batch, bin);
    }

    private BigDecimal num(Object v) {
        return new BigDecimal(String.valueOf(v));
    }

    /** 生成推荐：FIFO 首批 B1（早于 B2），含仓位拆分 */
    @Test
    void generateReturnsBatchAndBins() {
        Map<String, Object> out = service.generate("MATERIAL_OUT", ISSUE_NO);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lines = (List<Map<String, Object>>) out.get("lines");
        assertEquals(1, lines.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rec = (List<Map<String, Object>>) lines.get(0).get("recommendLines");
        assertEquals("B1", rec.get(0).get("batchNo"), "FIFO 首批 B1（入库 10-01）");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bins = (List<Map<String, Object>>) rec.get(0).get("bins");
        assertTrue(bins.size() >= 1, "位级拆分返回仓位");
        assertEquals(Boolean.TRUE, lines.get(0).get("satisfied"));
    }

    /** 确认回写两列且预留表零新增（spec 场景：确认回写不建预留，偏差 D2） */
    @Test
    void confirmWritesBacksWithoutReservation() {
        Map<String, Object> out = service.confirm(Map.of(
                "type", "MATERIAL_OUT",
                "docNo", ISSUE_NO,
                "lines", List.of(Map.of("lineNo", 1, "batchNo", "B1", "binCode", "BIN-02"))));

        assertEquals(1, out.get("updatedLines"));
        assertEquals(Boolean.FALSE, out.get("reservationCreated"));

        Map<String, Object> line = jdbc.queryForMap(
                "SELECT BATCH_NO, BIN_CODE FROM erp_inv_material_issue_line WHERE ISSUE_ID = ?",
                "pick-mi-1");
        assertEquals("B1", line.get("BATCH_NO"));
        assertEquals("BIN-02", line.get("BIN_CODE"));

        int resv = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sd_reservation "
                + "WHERE ITEM_CODE = ?", Integer.class, ITEM);
        assertEquals(0, resv, "确认不建预留行（偏差 D2）");

        // 4.7.1 任务生成钩子（picking-review 任务 2.2）：确认成功 → 任务自动生成
        int taskCnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_pick_task "
                + "WHERE SRC_DOC_NO = ?", Integer.class, ISSUE_NO);
        assertEquals(1, taskCnt, "确认后自动生成拣货任务");
        Map<String, Object> task = jdbc.queryForMap(
                "SELECT STATUS FROM erp_pick_task WHERE SRC_DOC_NO = ?", ISSUE_NO);
        assertEquals("CREATED", task.get("STATUS"));
        Map<String, Object> tline = jdbc.queryForMap(
                "SELECT BATCH_NO, BIN_CODE FROM erp_pick_task_line WHERE TASK_ID = "
                        + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO = ?)", ISSUE_NO);
        assertEquals("B1", tline.get("BATCH_NO"), "任务行与回写值一致");
        assertEquals("BIN-02", tline.get("BIN_CODE"));
    }

    /** 改写行落偏离台账（推荐 B1 → 指定 B2） */
    @Test
    void confirmRecordsDeviationOnRewrite() {
        service.confirm(Map.of(
                "type", "MATERIAL_OUT",
                "docNo", ISSUE_NO,
                "lines", List.of(Map.of("lineNo", 1, "batchNo", "B2",
                        "deviationReason", "B1 质检待复核"))));

        Map<String, Object> dv = jdbc.queryForMap(
                "SELECT RECOMMENDED_BATCH, ACTUAL_BATCH, REASON FROM erp_inv_batch_deviation "
                        + "WHERE ITEM_CODE = ?", ITEM);
        assertEquals("B1", dv.get("RECOMMENDED_BATCH"));
        assertEquals("B2", dv.get("ACTUAL_BATCH"));
        assertEquals("B1 质检待复核", dv.get("REASON"));
    }

    /** 改写无原因 422，行不回写 */
    @Test
    void confirmRewriteWithoutReasonRejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm(Map.of(
                "type", "MATERIAL_OUT",
                "docNo", ISSUE_NO,
                "lines", List.of(Map.of("lineNo", 1, "batchNo", "B2")))));
        assertEquals(422, ex.getCode());
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT BATCH_NO FROM erp_inv_material_issue_line WHERE ISSUE_ID = ?", "pick-mi-1");
        assertEquals("", line.get("BATCH_NO"), "422 回滚，行未回写");
        int rolled = jdbc.queryForObject("SELECT COUNT(*) FROM erp_pick_task "
                + "WHERE SRC_DOC_NO = ?", Integer.class, ISSUE_NO);
        assertEquals(0, rolled, "确认回滚时任务不生成（同事务）");
    }

    /** 已过账（非队列 A）单据拒绝推荐确认 */
    @Test
    void nonQueueADocRejected() {
        jdbc.update("UPDATE erp_inv_material_issue SET STATUS = 'POSTED' WHERE ISSUE_NO = ?",
                ISSUE_NO);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.generate(
                "MATERIAL_OUT", ISSUE_NO));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("待推荐"), ex.getMessage());
    }

    /** 行带 bin 过账：只扣目标位行，其他位行不动（任务 6.3/6.4） */
    @Test
    void postingWithSpecifiedBinDeductsOnlyThatBin() {
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = WH;
        l.itemCode = ITEM;
        l.batchNo = "B1";
        l.binCode = "BIN-02";
        l.qty = new BigDecimal("30");
        engine.post(StockPostingEngine.Request.of("MATERIAL_OUT", "MATERIAL_ISSUE",
                "PICK-" + (System.nanoTime() % 100000), List.of(l)));

        assertEquals(0, new BigDecimal("40").compareTo(num(binStock("B1", "BIN-01").get("QTY"))),
                "BIN-01 不动（seed 40）");
        assertEquals(0, new BigDecimal("30").compareTo(num(binStock("B1", "BIN-02").get("QTY"))),
                "BIN-02 60-30=30");
    }

    /** 指定仓位可用不足 → 422，不静默跨位补（spec 场景：指定仓位精确扣减） */
    @Test
    void specifiedBinInsufficientNoCrossBinFallback() {
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = WH;
        l.itemCode = ITEM;
        l.batchNo = "B1";
        l.binCode = "BIN-01";   // 可用 40
        l.qty = new BigDecimal("50");
        String doc = "PICK-BAD-" + (System.nanoTime() % 100000);

        ServiceException ex = assertThrows(ServiceException.class, () -> engine.post(
                StockPostingEngine.Request.of("MATERIAL_OUT", "MATERIAL_ISSUE", doc, List.of(l))));
        assertEquals(422, ex.getCode());
        // 两仓位分毫未动 + 无流水
        assertEquals(0, new BigDecimal("40").compareTo(num(binStock("B1", "BIN-01").get("QTY"))));
        assertEquals(0, new BigDecimal("60").compareTo(num(binStock("B1", "BIN-02").get("QTY"))));
        int txns = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_transaction "
                + "WHERE BIZ_DOC_NO = ?", Integer.class, doc);
        assertEquals(0, txns);
    }

    /** 回归：不带 bin 的指定批次过账仍跨位拆行（现行为不变） */
    @Test
    void withoutBinKeepsCrossBinSplit() {
        StockPostingEngine.Line l = new StockPostingEngine.Line();
        l.warehouseCode = WH;
        l.itemCode = ITEM;
        l.batchNo = "B1";
        l.qty = new BigDecimal("50");   // BIN-01 40 + BIN-02 10
        engine.post(StockPostingEngine.Request.of("MATERIAL_OUT", "MATERIAL_ISSUE",
                "PICK-SPLIT-" + (System.nanoTime() % 100000), List.of(l)));

        assertEquals(0, new BigDecimal("0").compareTo(num(binStock("B1", "BIN-01").get("QTY"))));
        assertEquals(0, new BigDecimal("50").compareTo(num(binStock("B1", "BIN-02").get("QTY"))));
    }

    /** 队列口径外类型 422 */
    @Test
    void unsupportedTypeRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.generate("NOPE", ISSUE_NO));
        assertEquals(422, ex.getCode());
    }

    /** 试算=引擎口径对拍（任务 3.3，design D1 缓解措施）：
     *  同一库存夹具下，BatchRecommendService 推荐序列与引擎 allocate() 缺省分配序列
     *  按批次聚合后必须一致（批次顺序、逐批取量）。 */
    @Test
    void recommendMatchesEngineAllocate() {
        // 夹具：B1 同日双位（10-01）、B2（10-05）、B3 效期更早但入库更晚（10-03）
        seed("B3", "", "80", "2026-10-03");
        // 加量到 B1/B2 使 120 的需求跨三批拆分
        jdbc.update("UPDATE erp_inv_stock SET QTY = 60, AVAILABLE_QTY = 60 WHERE ID = 'pick-st-B1-BIN-01'");
        jdbc.update("UPDATE erp_inv_stock SET QTY = 60, AVAILABLE_QTY = 60 WHERE ID = 'pick-st-B1-BIN-02'");

        // 试算侧（与引擎同量 120）
        Map<String, Object> rec = recommendService.recommend(
                com.erp.entity.inv.InvStock.DEFAULT_WH, ITEM, new BigDecimal("120"), false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recLines = (List<Map<String, Object>>) rec.get("lines");

        // 引擎侧（同仓同物料 120）
        var allocs = engine.allocate(com.erp.entity.inv.InvStock.DEFAULT_WH, ITEM,
                new BigDecimal("120"));

        // 引擎按位行返回 → 按批次聚合并保序
        java.util.LinkedHashMap<String, BigDecimal> eng = new java.util.LinkedHashMap<>();
        for (var a : allocs) {
            eng.merge(a.batchNo, a.qty, BigDecimal::add);
        }
        java.util.LinkedHashMap<String, BigDecimal> sim = new java.util.LinkedHashMap<>();
        for (Map<String, Object> l : recLines) {
            sim.merge(String.valueOf(l.get("batchNo")), (BigDecimal) l.get("take"), BigDecimal::add);
        }

        assertEquals(eng.keySet(), sim.keySet(), "批次序列一致（FIFO+FEFO 挑序）");
        for (String b : eng.keySet()) {
            assertEquals(0, eng.get(b).compareTo(sim.get(b)),
                    "批次 " + b + " 取量一致：引擎 " + eng.get(b) + " vs 试算 " + sim.get(b));
        }
    }
}
