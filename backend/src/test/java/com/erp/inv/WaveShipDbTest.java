package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvWave;
import com.erp.entity.inv.PickTask;
import com.erp.service.inv.PickDiffService;
import com.erp.service.inv.PickTaskService;
import com.erp.service.inv.WaveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集货发运 DB 集成（真实 MySQL，spec wave-management FR-4.4-7-5~7，任务 6.1/6.2/6.3）：
 * 分播差异登记与门闩拦截、闭环恢复、装车不一致按订单阻断（C-4.4-06）、
 * 逐单发运失败不扩散（BR-4.4-46）+ 单独重试 → 波次 CLOSED。
 */
@SpringBootTest
class WaveShipDbTest {

    private static final String WH = "WH-WVS";
    private static final String ITEM = "IT-WVS-01";
    private static final String RT = "wvs-route";

    @Autowired
    private WaveService waveService;
    @Autowired
    private PickTaskService pickTaskService;
    @Autowired
    private PickDiffService diffService;
    @Autowired
    private com.erp.service.sd.ShipmentService shipmentService;
    @Autowired
    private JdbcTemplate jdbc;

    private String waveId;
    private String taskId;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));

        jdbc.update("INSERT INTO erp_inv_route (ID, ROUTE_CODE, ROUTE_NAME, STATUS, CREATE_BY) "
                + "VALUES (?, 'WV-SR1', '发运测试线', 'ACTIVE', 'junit')", RT);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, "
                + "BASE_UNIT, MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('wvs-it', ?, '发运测试物料', '0001', 'PC', "
                + "'STRUCT', 'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        // avail 180 = 预留 90 + 需求 90：引擎批次预算 = avail − 批次预留（consume 在过账后）
        insertStock("wvs-st1", "WVS-B1", "BIN-A01", 80, "2026-09-01");
        insertStock("wvs-st2", "WVS-B1", "BIN-B02", 100, "2026-09-01");
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                + "('wvs-lg1','WVS-B1',?, '发运测试物料','2026-08-01','2027-12-31','0','1',"
                + "'junit')", ITEM);

        insertShipment("wvs-sh-a", "SH-WVS-A", "wvs-so-a");
        insertLine("wvs-sol-a", "wvs-sh-a", 1, 40, "wvs-so-a");
        insertShipment("wvs-sh-b", "SH-WVS-B", "wvs-so-b");
        insertLine("wvs-sol-b", "wvs-sh-b", 1, 50, "wvs-so-b");
        // ATP 预留（发运过账 consumeReservation 要求 ≥ 行量）
        // consumeReservation 查 LINE_ID = 发货行.SO_LINE_ID（= soId-L1），非发货行 ID
        insertReservation("wvs-rsv-a", "wvs-so-a", "wvs-so-a-L1", 40);
        insertReservation("wvs-rsv-b", "wvs-so-b", "wvs-so-b-L1", 50);

        jdbc.update("INSERT INTO erp_inv_wave (ID, WAVE_NO, CLUSTER_TYPE, CLUSTER_KEY, STATUS, "
                + "DOC_COUNT, CREATE_BY) VALUES ('wvs-wave','WVST0001','ROUTE',?,'CREATED',2,"
                + "'junit')", RT);
        waveId = "wvs-wave";
        jdbc.update("INSERT INTO erp_inv_wave_doc (ID, WAVE_ID, SHIP_ID, SHIP_NO, BIND_STATUS, "
                + "SORT_STATUS, LOAD_STATUS, CREATE_BY) VALUES "
                + "('wvs-doc-a','wvs-wave','wvs-sh-a','SH-WVS-A','BOUND','PENDING','PENDING',"
                + "'junit'), ('wvs-doc-b','wvs-wave','wvs-sh-b','SH-WVS-B','BOUND','PENDING',"
                + "'PENDING','junit')");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    // ---------- 主链路 ----------

    @Test
    void fullFlowToClosed() {
        reachSorting();
        // 分播：A 平、B 平 → 全 PASSED → STAGING
        Map<String, Object> sa = waveService.sortConfirm(waveId, "wvs-sh-a",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 40)));
        assertEquals(Boolean.TRUE, sa.get("passed"));
        Map<String, Object> sb = waveService.sortConfirm(waveId, "wvs-sh-b",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 50)));
        assertEquals(Boolean.TRUE, sb.get("passed"));
        assertEquals(InvWave.ST_STAGING, waveStatus(), "全单分播通过 → STAGING");

        // 装车：一致扫描 → LOADED；全 LOADED → SHIPPING
        waveService.loadConfirm(waveId, "wvs-sh-a",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 40)));
        waveService.loadConfirm(waveId, "wvs-sh-b",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 50)));
        assertEquals(InvWave.ST_SHIPPING, waveStatus(), "全单装车完成 → SHIPPING");

        // 发运：逐单过账 → 全 POSTED → CLOSED
        Map<String, Object> ship = waveService.shipConfirm(waveId);
        assertEquals(2, ((Number) ship.get("posted")).intValue(), "两单全过账");
        assertEquals(0, ((Number) ship.get("failed")).intValue());
        assertEquals(InvWave.ST_CLOSED, waveStatus(), "全 POSTED → CLOSED");
        String aSt = jdbc.queryForObject("SELECT STATUS FROM erp_sd_shipment WHERE ID = ?",
                String.class, "wvs-sh-a");
        assertEquals("POSTED", aSt);
        // 分配段精确扣减（WAVE 段过账）：段 = 池连续消费 → A: A01:40；B: A01:40+B02:10
        assertEquals(0, qtyOf("wvs-st1"), "BIN-A01 80−40−40=0（B 跨段先用 A01 余量）");
        assertEquals(90, qtyOf("wvs-st2"), "BIN-B02 100−10=90");
    }

    // ---------- 6.1 分播差异 + 门闩 ----------

    @Test
    void sortDiffLocksPostingUntilClosed() {
        reachSorting();
        // B 实点 48 ≠ 50 → WAVE_SORT 差异
        Map<String, Object> bad = waveService.sortConfirm(waveId, "wvs-sh-b",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 48)));
        assertEquals(Boolean.FALSE, bad.get("passed"), "不平不置 PASSED");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_check_diff "
                + "WHERE DIFF_TYPE = 'WAVE_SORT' AND SRC_DOC_NO = 'SH-WVS-B' "
                + "AND STATUS = 'PENDING'", Integer.class), "差异登记");

        // 门闩：直接过账 B → 422（WAVE_SORT 同锁）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> shipmentService.post("wvs-sh-b"));
        assertEquals(422, ex.getCode(), "WAVE_SORT 未闭环锁过账");

        // 闭环 → 恢复可装车/发运
        String diffId = jdbc.queryForObject("SELECT ID FROM erp_inv_check_diff "
                + "WHERE DIFF_TYPE = 'WAVE_SORT' AND SRC_DOC_NO = 'SH-WVS-B'",
                String.class);
        diffService.close(diffId, "分播差 2 件已补拣复核");
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_check_diff WHERE ID = ?", String.class, diffId));

        // A 分播通过后 B 走闭环路径装车（无快照以扫描为基准）→ 全就绪 STAGING
        waveService.sortConfirm(waveId, "wvs-sh-a",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 40)));
        waveService.loadConfirm(waveId, "wvs-sh-a",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 40)));
        waveService.loadConfirm(waveId, "wvs-sh-b",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 50)));
        assertEquals(InvWave.ST_SHIPPING, waveStatus(), "闭环后装车推进 SHIPPING");
    }

    // ---------- 6.2 装车阻断 ----------

    @Test
    void loadMismatchBlocksSingleOrderOnly() {
        reachSorting();
        waveService.sortConfirm(waveId, "wvs-sh-a",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 40)));
        waveService.sortConfirm(waveId, "wvs-sh-b",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 50)));
        // A 装车扫描 8 ≠ 复核 40 → C-4.4-06 阻断该单（不落差异单）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> waveService.loadConfirm(waveId, "wvs-sh-a",
                        List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 8))));
        assertEquals(422, ex.getCode());
        assertTrue(String.valueOf(ex.getMessage()).contains("C-4.4-06"), "提示卡控编号");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_check_diff "
                + "WHERE DIFF_TYPE = 'WAVE_SORT'", Integer.class), "装车比对纯校验不落差异单");
        // 波次未推进
        assertEquals(InvWave.ST_STAGING, waveStatus(), "A 阻断不影响波次状态");
        // B 照常装车成功（他单不受影响）
        Map<String, Object> ok = waveService.loadConfirm(waveId, "wvs-sh-b",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 50)));
        assertEquals(Boolean.TRUE, ok.get("loaded"), "波次内其他订单不受影响（BR-4.4-45）");
    }

    // ---------- 6.3 发运失败不扩散 + 重试 ----------

    @Test
    void shipFailureIsolatedThenRetryCloses() {
        reachSorting();
        waveService.sortConfirm(waveId, "wvs-sh-a",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 40)));
        waveService.sortConfirm(waveId, "wvs-sh-b",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 50)));
        waveService.loadConfirm(waveId, "wvs-sh-a",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 40)));
        waveService.loadConfirm(waveId, "wvs-sh-b",
                List.of(Map.of("itemCode", ITEM, "batchNo", "WVS-B1", "qty", 50)));

        // 造 B 过账失败：B 挂 PENDING 的 WAVE_SORT 差异 → 门闩按订单 422
        //（真实且隔离：砍库存会污染批次级预算连坐 A —— BR-4.4-46 单失败不扩散）
        jdbc.update("INSERT INTO erp_inv_check_diff (ID, DIFF_TYPE, SRC_DOC_TYPE, SRC_DOC_NO, "
                + "WAVE_ID, WAVE_DOC_ID, DIFF_KIND, ITEM_CODE, DIFF_NOTE, STATUS, CREATE_BY) "
                + "VALUES ('wvs-diff-x','WAVE_SORT','SALES_OUT','SH-WVS-B','wvs-wave',"
                + "'wvs-doc-b','QTY',?,'发运前差异注入（测试）','PENDING','junit')", ITEM);

        Map<String, Object> res = waveService.shipConfirm(waveId);
        assertEquals(1, ((Number) res.get("posted")).intValue(), "A 照常过账（单失败不扩散）");
        assertEquals(1, ((Number) res.get("failed")).intValue(), "B 失败记录");
        assertEquals(InvWave.ST_SHIPPING, waveStatus(), "有失败单停在 SHIPPING");
        String err = jdbc.queryForObject("SELECT SHIP_ERR FROM erp_inv_wave_doc WHERE ID = ?",
                String.class, "wvs-doc-b");
        assertTrue(err != null && !err.isEmpty(), "失败原因留痕");
        String aSt = jdbc.queryForObject("SELECT STATUS FROM erp_sd_shipment WHERE ID = ?",
                String.class, "wvs-sh-a");
        assertEquals("POSTED", aSt, "A 已过账");

        // 闭环差异 → 单独重试 B → 成功 → CLOSED
        jdbc.update("UPDATE erp_inv_check_diff SET STATUS = 'RESOLVED', RESOLVE_NOTE = '测试闭环' "
                + "WHERE ID = 'wvs-diff-x'");
        Map<String, Object> retry = waveService.retryShip(waveId, "wvs-sh-b");
        assertEquals(Boolean.TRUE, retry.get("posted"), "重试成功");
        assertEquals(InvWave.ST_CLOSED, waveStatus(), "全部 POSTED → CLOSED");
        String errAfter = jdbc.queryForObject("SELECT SHIP_ERR FROM erp_inv_wave_doc "
                + "WHERE ID = ?", String.class, "wvs-doc-b");
        assertTrue(errAfter == null || errAfter.isEmpty(), "重试成功清失败原因");
    }

    // ---------- 工具 ----------

    /** 创建+确认分配 → 任务推进 PICKED → 波次 SORTING */
    private void reachSorting() {
        waveService.allocate(waveId);
        waveService.confirmAllocate(waveId);
        taskId = jdbc.queryForObject("SELECT ID FROM erp_pick_task "
                + "WHERE SRC_TYPE = 'WAVE' AND SRC_DOC_NO = 'WVST0001'", String.class);
        pickTaskService.transition(taskId, PickTask.ST_CREATED, PickTask.ST_PICKING);
        pickTaskService.transition(taskId, PickTask.ST_PICKING, PickTask.ST_PICKED);
        assertEquals(InvWave.ST_SORTING, waveStatus(), "任务拣毕 → 波次 SORTING");
    }

    private String waveStatus() {
        return jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave WHERE ID = ?",
                String.class, waveId);
    }

    private int qtyOf(String stockId) {
        return jdbc.queryForObject("SELECT CAST(QTY AS SIGNED) FROM erp_inv_stock WHERE ID = ?",
                Integer.class, stockId);
    }

    private void insertStock(String id, String batch, String bin, int qty, String inbound) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                + "BATCH_NO, BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, "
                + "CREATE_BY) VALUES (?, ?, ?, '发运测试物料', ?, ?, ?, 0, 0, ?, ?, 'junit')",
                id, WH, ITEM, batch, bin, qty, qty, inbound);
    }

    private void insertShipment(String id, String shipNo, String soId) {
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, "
                + "CUSTOMER_CODE, CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, TOTAL_AMT, "
                + "ROUTE_ID, CREATE_BY) VALUES (?, ?, 'PARTIAL', ?, 'C-WVS', '发运测试客户', ?, "
                + "'DRAFT', 0, 100, ?, 'junit')", id, shipNo, soId, WH, RT);
    }

    private void insertLine(String id, String shipId, int lineNo, int qty, String soId) {
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, "
                + "SO_LINE_ID, SO_NO, SO_LINE_NO, ITEM_CODE, ITEM_NAME, QTY, WAREHOUSE_CODE, "
                + "LINE_STATUS, CREATE_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '发运测试物料', ?, ?, "
                + "'PENDING', 'junit')",
                id, shipId, lineNo, soId, soId + "-L1", "SO-" + soId, lineNo, ITEM, qty, WH);
    }

    private void insertReservation(String id, String soId, String lineId, int qty) {
        jdbc.update("INSERT INTO erp_sd_reservation (ID, SO_ID, SO_NO, LINE_ID, LINE_NO, "
                + "ITEM_CODE, WAREHOUSE_CODE, BATCH_NO, QTY, STATUS, LOCK_AT, CREATE_BY) VALUES "
                + "(?, ?, ?, ?, 1, ?, ?, 'WVS-B1', ?, 'ACTIVE', NOW(), 'junit')",
                id, soId, "SO-" + soId, lineId, ITEM, WH, qty);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE '%SH-WVS-%' "
                + "OR PAYLOAD LIKE '%SH-WVS-%'");
        jdbc.update("DELETE FROM erp_inv_check_diff WHERE DIFF_TYPE = 'WAVE_SORT' "
                + "OR SRC_DOC_NO LIKE 'SH-WVS-%'");
        jdbc.update("DELETE FROM erp_pick_task_line WHERE TASK_ID IN (SELECT ID FROM "
                + "erp_pick_task WHERE SRC_DOC_NO IN ('WVST0001') OR SRC_DOC_NO LIKE 'SH-WVS-%')");
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO IN ('WVST0001') "
                + "OR SRC_DOC_NO LIKE 'SH-WVS-%'");
        jdbc.update("DELETE FROM erp_inv_wave_line WHERE WAVE_ID LIKE 'wvs-%'");
        jdbc.update("DELETE FROM erp_inv_wave_doc WHERE WAVE_ID LIKE 'wvs-%'");
        jdbc.update("DELETE FROM erp_inv_wave WHERE ID LIKE 'wvs-%'");
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE ID LIKE 'wvs-%'");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID LIKE 'wvs-%'");
        jdbc.update("DELETE FROM erp_sd_reservation WHERE ID LIKE 'wvs-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID LIKE 'wvs-%'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ID LIKE 'wvs-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ID = 'wvs-it'");
        jdbc.update("DELETE FROM erp_inv_route WHERE ID = ?", RT);
    }
}
