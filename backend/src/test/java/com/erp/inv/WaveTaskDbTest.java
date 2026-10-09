package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvWave;
import com.erp.entity.inv.PickTask;
import com.erp.service.approval.ApprovalEngine;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAVE 任务 DB 集成（真实 MySQL，spec wave-management FR-4.4-7-3，任务 5.1/5.2/5.3）：
 * 聚合行+SRC_ALLOC+仓位序、确认分配生成恰好 1 张、幂等重建、
 * 任务状态连带波次（PICKING/SORTING）、作废撤销任务、4.6.3 钩子三分支。
 */
@SpringBootTest
class WaveTaskDbTest {

    private static final String WH = "WH-WVT";
    private static final String ITEM = "IT-WVT-01";
    private static final String RT = "wvt-route";

    @Autowired
    private WaveService waveService;
    @Autowired
    private PickTaskService pickTaskService;
    @Autowired
    private com.erp.service.inv.PickRecommendService recommendSvc;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    private String waveId;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));

        jdbc.update("INSERT INTO erp_inv_route (ID, ROUTE_CODE, ROUTE_NAME, STATUS, CREATE_BY) "
                + "VALUES (?, 'WV-TR1', '任务测试线', 'ACTIVE', 'junit')", RT);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, "
                + "BASE_UNIT, MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('wvt-it', ?, '任务测试物料', '0001', 'PC', "
                + "'STRUCT', 'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        // 两仓位（验证仓位号升序）：BIN-B02 与 BIN-A01 同批次
        insertStock("wvt-st1", "WVT-B1", "BIN-B02", 50, "2026-09-01");
        insertStock("wvt-st2", "WVT-B1", "BIN-A01", 40, "2026-09-01");
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                + "('wvt-lg1','WVT-B1',?, '任务测试物料','2026-08-01','2027-12-31','0','1',"
                + "'junit')", ITEM);

        // 两张 DRAFT 发货单（单号序：A 先 B 后），需求 40+50 = 90（库存 90 刚好）
        insertShipment("wvt-sh-a", "SH-WVT-A", "wvt-so-a");
        insertLine("wvt-sol-a", "wvt-sh-a", 1, 40, "wvt-so-a");
        insertShipment("wvt-sh-b", "SH-WVT-B", "wvt-so-b");
        insertLine("wvt-sol-b", "wvt-sh-b", 1, 50, "wvt-so-b");

        jdbc.update("INSERT INTO erp_inv_wave (ID, WAVE_NO, CLUSTER_TYPE, CLUSTER_KEY, STATUS, "
                + "DOC_COUNT, CREATE_BY) VALUES ('wvt-wave','WVTT0001','ROUTE',?,'CREATED',2,"
                + "'junit')", RT);
        waveId = "wvt-wave";
        jdbc.update("INSERT INTO erp_inv_wave_doc (ID, WAVE_ID, SHIP_ID, SHIP_NO, BIND_STATUS, "
                + "SORT_STATUS, LOAD_STATUS, CREATE_BY) VALUES "
                + "('wvt-doc-a','wvt-wave','wvt-sh-a','SH-WVT-A','BOUND','PENDING','PENDING',"
                + "'junit'), ('wvt-doc-b','wvt-wave','wvt-sh-b','SH-WVT-B','BOUND','PENDING',"
                + "'PENDING','junit')");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirmAllocateGeneratesSingleWaveTaskWithAggregatedLines() {
        waveService.allocate(waveId);
        waveService.confirmAllocate(waveId);

        // 恰好 1 张 WAVE 任务
        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT ID, TASK_NO, STATUS FROM erp_pick_task WHERE SRC_TYPE = 'WAVE' "
                        + "AND SRC_DOC_NO = 'WVTT0001'");
        assertEquals(1, tasks.size(), "恰好 1 张 WAVE 任务");
        assertEquals(PickTask.ST_CREATED, tasks.get(0).get("STATUS"));

        // 行聚合 + 仓位号升序（BIN-A01 在前，BIN-B02 在后）
        List<Map<String, Object>> lines = jdbc.queryForList(
                "SELECT LINE_NO, BIN_CODE, BATCH_NO, QTY, SRC_ALLOC FROM erp_pick_task_line "
                        + "WHERE TASK_ID = ? ORDER BY LINE_NO", tasks.get(0).get("ID"));
        assertEquals(2, lines.size(), "两仓位 → 两聚合行");
        assertEquals("BIN-A01", lines.get(0).get("BIN_CODE"), "仓位号升序（D5 路径）");
        assertEquals("BIN-B02", lines.get(1).get("BIN_CODE"));
        // 归属明细 JSON：A 单 40 先到 → A 走 BIN-A01 的 40（全在 A01）；B 走 B02 50
        String alloc0 = String.valueOf(lines.get(0).get("SRC_ALLOC"));
        assertTrue(alloc0.contains("SH-WVT-A"), "归属明细含单号：" + alloc0);
        assertEquals(0, new BigDecimal("40").compareTo(new BigDecimal(
                String.valueOf(lines.get(0).get("QTY")))), "A01 行 = A 单 40");

        // 幂等：再次生成不新增
        pickTaskService.createFromWave(waveId);
        int cnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_pick_task "
                + "WHERE SRC_TYPE = 'WAVE' AND SRC_DOC_NO = 'WVTT0001'", Integer.class);
        assertEquals(1, cnt, "幂等：不重复生成");
        int lineCnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_pick_task_line "
                + "WHERE TASK_ID = ? AND DEL_FLAG = '0'", Integer.class,
                tasks.get(0).get("ID"));
        assertEquals(2, lineCnt, "幂等重建不产生重复有效行（旧行软删 @TableLogic）");
    }

    @Test
    void taskStatusDrivesWaveStatus() {
        waveService.allocate(waveId);
        waveService.confirmAllocate(waveId);
        String taskId = jdbc.queryForObject("SELECT ID FROM erp_pick_task "
                + "WHERE SRC_TYPE = 'WAVE' AND SRC_DOC_NO = 'WVTT0001'", String.class);
        assertEquals(InvWave.ST_ALLOCATED, waveStatus(), "确认分配 → ALLOCATED");

        // 任务 CREATED→PICKING → 波次 PICKING（5.3 连带）
        pickTaskService.transition(taskId, PickTask.ST_CREATED, PickTask.ST_PICKING);
        assertEquals(InvWave.ST_PICKING, waveStatus(), "任务开拣 → 波次 PICKING");

        // 任务 → PICKED → 波次 SORTING（拣毕进分播）
        pickTaskService.transition(taskId, PickTask.ST_PICKING, PickTask.ST_PICKED);
        assertEquals(InvWave.ST_SORTING, waveStatus(), "任务拣毕 → 波次 SORTING");
    }

    @Test
    void waveCancelCancelsTask() {
        waveService.allocate(waveId);
        waveService.confirmAllocate(waveId);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM erp_pick_task "
                + "WHERE SRC_TYPE = 'WAVE' AND SRC_DOC_NO = 'WVTT0001' AND STATUS = 'CREATED'",
                Integer.class));

        waveService.cancel(waveId, "需求变更作废");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_pick_task "
                + "WHERE SRC_TYPE = 'WAVE' AND SRC_DOC_NO = 'WVTT0001'", String.class);
        assertEquals(PickTask.ST_CANCELLED, st, "波次作废 → WAVE 任务撤销");
        // 单据解绑
        int bound = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_wave_doc "
                + "WHERE WAVE_ID = ? AND BIND_STATUS = 'BOUND'", Integer.class, waveId);
        assertEquals(0, bound, "全部解绑");
    }

    @Test
    void confirmHookThreeBranches() {
        com.erp.service.inv.PickRecommendService recommend = recommendSvc;
        // 分支 1：非成员单据 → 原路径生成单据级任务（先插一张不入波次的发货单）
        insertShipment("wvt-sh-c", "SH-WVT-C", "wvt-so-c");
        insertLine("wvt-sol-c", "wvt-sh-c", 1, 10, "wvt-so-c");
        Map<String, Object> r1 = recommend.confirm(hookPayload("SH-WVT-C"));
        assertEquals(Boolean.FALSE, r1.get("waveSkipped"), "非成员走原路径");
        assertNotNull(r1.get("taskNo"), "非成员生成单据级任务");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM erp_pick_task "
                + "WHERE SRC_DOC_NO = 'SH-WVT-C'", Integer.class));

        // 分支 2：CREATED 波次成员 → 跳过任务仅回写
        Map<String, Object> r2 = recommend.confirm(hookPayload("SH-WVT-A"));
        assertEquals(Boolean.TRUE, r2.get("waveSkipped"), "CREATED 成员跳过单据任务");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM erp_pick_task "
                + "WHERE SRC_DOC_NO = 'SH-WVT-A'", Integer.class), "不生成单据级任务");
        String written = jdbc.queryForObject(
                "SELECT BATCH_NO FROM erp_sd_shipment_line WHERE ID = 'wvt-sol-a'", String.class);
        assertEquals("WVT-B1", written, "行值已回写");

        // 分支 3：≥ALLOCATED 成员 → 422（防绕过改批审批）
        waveService.allocate(waveId);
        waveService.confirmAllocate(waveId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> recommend.confirm(hookPayload("SH-WVT-B")));
        assertEquals(422, ex.getCode());
        assertTrue(String.valueOf(ex.getMessage()).contains("C-4.4-08"), "提示走波次改批审批");
    }

    private Map<String, Object> hookPayload(String shipNo) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("type", "SALES_OUT");
        p.put("docNo", shipNo);
        p.put("lines", List.of(Map.of("lineNo", 1, "batchNo", "WVT-B1")));
        return p;
    }

    // ---------- 夹具 ----------

    private String waveStatus() {
        return jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave WHERE ID = ?",
                String.class, waveId);
    }

    private void insertStock(String id, String batch, String bin, int qty, String inbound) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                + "BATCH_NO, BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, "
                + "CREATE_BY) VALUES (?, ?, ?, '任务测试物料', ?, ?, ?, 0, 0, ?, ?, 'junit')",
                id, WH, ITEM, batch, bin, qty, qty, inbound);
    }

    private void insertShipment(String id, String shipNo, String soId) {
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, "
                + "CUSTOMER_CODE, CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, TOTAL_AMT, "
                + "ROUTE_ID, CREATE_BY) VALUES (?, ?, 'PARTIAL', ?, 'C-WVT', '任务测试客户', ?, "
                + "'DRAFT', 0, 0, ?, 'junit')", id, shipNo, soId, WH, RT);
    }

    private void insertLine(String id, String shipId, int lineNo, int qty, String soId) {
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, "
                + "SO_LINE_ID, SO_NO, SO_LINE_NO, ITEM_CODE, ITEM_NAME, QTY, WAREHOUSE_CODE, "
                + "LINE_STATUS, CREATE_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '任务测试物料', ?, ?, "
                + "'PENDING', 'junit')",
                id, shipId, lineNo, soId, soId + "-L1", "SO-" + soId, lineNo, ITEM, qty, WH);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pick_task_line WHERE TASK_ID IN (SELECT ID FROM "
                + "erp_pick_task WHERE SRC_DOC_NO IN ('WVTT0001') OR SRC_DOC_NO LIKE 'SH-WVT-%')");
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO IN ('WVTT0001') "
                + "OR SRC_DOC_NO LIKE 'SH-WVT-%'");
        jdbc.update("DELETE FROM erp_inv_wave_line WHERE WAVE_ID LIKE 'wvt-%'");
        jdbc.update("DELETE FROM erp_inv_wave_doc WHERE WAVE_ID LIKE 'wvt-%'");
        jdbc.update("DELETE FROM erp_inv_wave_adjust WHERE WAVE_ID LIKE 'wvt-%'");
        jdbc.update("DELETE FROM erp_inv_wave WHERE ID LIKE 'wvt-%'");
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE ID LIKE 'wvt-%'");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID LIKE 'wvt-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID LIKE 'wvt-%'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ID LIKE 'wvt-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ID = 'wvt-it'");
        jdbc.update("DELETE FROM erp_inv_route WHERE ID = ?", RT);
    }
}
