package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;
import com.erp.service.inv.FreezeService;
import com.erp.service.inv.RecallService;
import com.erp.service.inv.ScrapOrderService;
import com.erp.service.inv.TraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追溯召回全链 DB 语义（真实 MySQL，spec trace-recall，任务 6.3/7.3/10.1/10.2）：
 * 三索引归一与重复/歧义拦截、五类流向物化含缺口标记、批量冻结+释放预留、
 * 拦截失败升级召回、应召定格、受限区入库 L1、报废 qcFirst 核销与处置回调、
 * 结案召回率、召回未结案解冻拦截、动作级分权。
 */
@SpringBootTest
class TraceRecallDbTest {

    private static final String WH = "WH-TRC";
    private static final String ITEM = "IT-TRC-01";
    private static final String BIN_STORE = "TRC-STORE-1";
    private static final String BIN_SCRAP = "TRC-SCRAP-1";
    private static final String BIN_RETURN = "TRC-RETURN-1";

    @Autowired
    private TraceService traceService;
    @Autowired
    private RecallService recallService;
    @Autowired
    private FreezeService freezeService;
    @Autowired
    private ScrapOrderService scrapOrderService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        cleanup();
        jdbc.update("INSERT INTO erp_inv_warehouse (ID, WH_CODE, WH_NAME, STATUS, CREATE_BY) "
                + "VALUES ('trc-wh', ?, '追溯测试仓', '1', 'junit')", WH);
        seedBin("trc-b-store", BIN_STORE, "STORE");
        seedBin("trc-b-scrap", BIN_SCRAP, "SCRAP");
        seedBin("trc-b-return", BIN_RETURN, "RETURN");
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STANDARD_COST, STATUS, CREATE_BY) VALUES ('trc-it-01', ?, '追溯召回测试物料', "
                + "'0001', 'PCS', 'G001', 'BUY', 'NORMAL', '1', '0', 10, '1', 'junit')", ITEM);
        seedBatch("trc-led-1", "TRC-B1", "SB-TRC-1");
        seedBatch("trc-led-2", "TRC-B2", "SB-TRC-2");
        seedBatch("trc-led-3", "TRC-B3", "SB-TRC-DUP");
        seedBatch("trc-led-4", "TRC-B4", "SB-TRC-DUP");
        seedBatch("trc-led-5", "TRC-B5", null);
        seedBatch("trc-led-6", "TRC-B6", null);
        seedBatch("trc-led-7", "TRC-B7", null);
        jdbc.update("INSERT INTO erp_inv_serial (ID, SERIAL_NO, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "STATUS, CREATE_BY) VALUES ('trc-sn-1', 'SN-TRC-1', ?, '追溯召回测试物料', "
                + "'TRC-B1', 'INSTOCK', 'junit')", ITEM);
        // TRC-B1：可用 70 + 冻结 30（同一位行两列）
        seedStock("trc-stk-1", "TRC-B1", BIN_STORE, "100", "30", "70");
        seedStock("trc-stk-5", "TRC-B5", BIN_STORE, "50", "0", "50");
        seedStock("trc-stk-6", "TRC-B6", BIN_STORE, "100", "0", "100");
        seedStock("trc-stk-7", "TRC-B7", BIN_STORE, "50", "0", "50");
        // TRC-B5 ACTIVE 预留（冻结须释放 BR-4.4-49）
        jdbc.update("INSERT INTO erp_sd_reservation (ID, SO_ID, SO_NO, LINE_ID, LINE_NO, "
                + "ITEM_CODE, WAREHOUSE_CODE, BATCH_NO, QTY, STATUS, LOCK_AT, CREATE_BY) "
                + "VALUES ('trc-rv-5', 'trc-so-5', 'SO-TRC-5', 'trc-so-5-L1', 1, ?, ?, "
                + "'TRC-B5', 20, 'ACTIVE', NOW(), 'junit')", ITEM, WH);
        // 调拨在途（OUT_POSTED 未 IN_POSTED）
        jdbc.update("INSERT INTO erp_inv_transfer_order (ID, TRANSFER_NO, OUT_WH_CODE, "
                + "IN_WH_CODE, OUT_LE_CODE, IN_LE_CODE, STATUS, TOTAL_QTY, CREATE_BY) "
                + "VALUES ('trc-to-1', 'TRC-TO-1', ?, 'WH-MAIN', 'LE1', 'LE1', 'OUT_POSTED', "
                + "30, 'junit')", WH);
        jdbc.update("INSERT INTO erp_inv_transfer_order_line (ID, ORDER_ID, LINE_NO, ITEM_CODE, "
                + "ITEM_NAME, BATCH_NO, QTY, INTERNAL_PRICE, CREATE_BY) "
                + "VALUES ('trc-tol-1', 'trc-to-1', 1, ?, '追溯召回测试物料', 'TRC-B1', 30, 1, "
                + "'junit')", ITEM);
        // 发运：未签收 10 / 已签收 40（C1）+ 20（C2）
        seedShipment("trc-sh-1", "SH-TRC-1", "POSTED", null, "C1", "客户甲", false);
        seedShipmentLine("trc-shl-1", "trc-sh-1", "TRC-B1", 10);
        seedShipment("trc-sh-2", "SH-TRC-2", "SIGNED", "2026-10-01 10:00:00", "C1", "客户甲", true);
        seedShipmentLine("trc-shl-2", "trc-sh-2", "TRC-B1", 40);
        seedShipment("trc-sh-3", "SH-TRC-3", "SIGNED", "2026-10-02 11:00:00", "C2", "客户乙", true);
        seedShipmentLine("trc-shl-3", "trc-sh-3", "TRC-B1", 20);
        // 结案测试：TRC-B6 已签收 100
        seedShipment("trc-sh-4", "SH-TRC-4", "SIGNED", "2026-10-03 09:00:00", "C3", "客户丙", true);
        seedShipmentLine("trc-shl-4", "trc-sh-4", "TRC-B6", 100);
        // 数据缺口：同物料、批次字段为空
        seedShipment("trc-sh-5", "SH-TRC-5", "POSTED", null, "C4", "客户丁", false);
        seedShipmentLine("trc-shl-5", "trc-sh-5", "", 15);
        login("qeng-trc", "ROLE_QUALITY_ENG");
    }

    @AfterEach
    void afterEach() {
        cleanup();
        SecurityContextHolder.clearContext();
    }

    private void cleanup() {
        jdbc.update("DELETE t FROM erp_inv_trace_log t JOIN erp_inv_trace_order o "
                + "ON t.TRACE_ID = o.ID WHERE o.ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_trace_flow WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_trace_order WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE ID LIKE 'trc-rv-%'");
        // 冻结/解冻审批实例（跨轮遗留会撞「解冻审批中」）
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a "
                + "ON t.APPR_ID = a.ID WHERE a.BIZ_TYPE IN ('Freeze','Unfreeze') "
                + "AND (a.BIZ_ID LIKE 'trc-%' OR a.BIZ_ID IN "
                + "(SELECT ID FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ?))", WH);
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('Freeze','Unfreeze') "
                + "AND (BIZ_ID LIKE 'trc-%' OR BIZ_ID IN "
                + "(SELECT ID FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ?))", WH);
        jdbc.update("DELETE FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE l FROM erp_sd_shipment_line l JOIN erp_sd_shipment s "
                + "ON s.ID = l.SHIP_ID WHERE s.ID LIKE 'trc-sh-%'");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID LIKE 'trc-sh-%'");
        jdbc.update("DELETE FROM erp_inv_transfer_order_line WHERE ORDER_ID LIKE 'trc-to-%'");
        jdbc.update("DELETE FROM erp_inv_transfer_order WHERE ID LIKE 'trc-to-%'");
        // 凭证先于报废单删（子查询按 SCRAP_NO 关联）
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'SCRAP' "
                + "AND SOURCE_DOC_NO IN (SELECT SCRAP_NO FROM erp_inv_scrap_order "
                + "WHERE WAREHOUSE_CODE = ?)", WH);
        jdbc.update("DELETE FROM erp_inv_scrap_order WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_serial WHERE SERIAL_NO = 'SN-TRC-1'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_bin WHERE WH_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_warehouse WHERE WH_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_ops_outbox WHERE PAYLOAD LIKE '%" + ITEM + "%' "
                + "OR PAYLOAD LIKE '%TRC-%'");
    }

    // ================= 10.1 三索引归一与物化 =================

    @Test
    void analyzeThreeIndexesAndMaterialize() {
        // 序列号索引 → 归一到 TRC-B1，五类流向 + 缺口标记
        Map<String, Object> o = traceService.analyze("SERIAL", "SN-TRC-1", "外观锈蚀批次缺陷");
        assertEquals("EXECUTING", o.get("status"));
        assertEquals("TRC-B1", o.get("batchNo"));
        assertNotNull(o.get("traceNo"));

        Map<String, Object> d = traceService.detail(String.valueOf(o.get("id")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flows = (List<Map<String, Object>>) d.get("flows");
        Set<String> types = new java.util.HashSet<>();
        int checks = 0;
        for (Map<String, Object> f : flows) {
            types.add(String.valueOf(f.get("flowType")));
            if ("1".equals(String.valueOf(f.get("checkFlag")))) {
                checks++;
            }
        }
        assertTrue(types.contains("STOCK_AVAILABLE"), "缺在库可用：" + types);
        assertTrue(types.contains("STOCK_FROZEN"), "缺在库冻结：" + types);
        assertTrue(types.contains("IN_TRANSIT"), "缺调拨在途：" + types);
        assertTrue(types.contains("OUT_UNSIGNED"), "缺未签收：" + types);
        assertTrue(types.contains("OUT_SIGNED"), "缺已签收：" + types);
        assertEquals(1, checks, "缺口行应标待人工核查");
        // 审计回放（C-4.4-10）：ANALYZE + MATERIALIZE
        assertTrue(traceService.auditLogs(String.valueOf(o.get("id"))).size() >= 2);

        // 重复发起 → 422（展示既有单号）
        ServiceException dup = assertThrows(ServiceException.class,
                () -> traceService.analyze("BATCH", "TRC-B1", "再次发起"));
        assertEquals(422, dup.getCode());
        assertTrue(dup.getMessage().contains(String.valueOf(o.get("traceNo"))), dup.getMessage());

        // 供应商批次号正常归一（TRC-B2）
        Map<String, Object> o2 = traceService.analyze("SUPPLIER_BATCH", "SB-TRC-2", "供应商批次缺陷");
        assertEquals("TRC-B2", o2.get("batchNo"));

        // 歧义：SB-TRC-DUP 对应两批次 → 422
        ServiceException amb = assertThrows(ServiceException.class,
                () -> traceService.analyze("SUPPLIER_BATCH", "SB-TRC-DUP", "歧义"));
        assertEquals(422, amb.getCode());
        assertTrue(amb.getMessage().contains("歧义"), amb.getMessage());

        // 不存在 → 422 核对批次信息
        ServiceException nf = assertThrows(ServiceException.class,
                () -> traceService.analyze("BATCH", "TRC-NOPE", "不存在"));
        assertTrue(nf.getMessage().contains("核对批次信息"), nf.getMessage());
        ServiceException ns = assertThrows(ServiceException.class,
                () -> traceService.analyze("SERIAL", "SN-NOPE", "序列不存在"));
        assertTrue(ns.getMessage().contains("核对批次信息"), ns.getMessage());

        // 动作级分权（10.2）：WAREHOUSE 发起 → 403
        login("wh-trc", "ROLE_WAREHOUSE");
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> traceService.analyze("BATCH", "TRC-B3", "仓库越权"));
        assertEquals(403, forbidden.getCode());
    }

    // ================= 10.1 批量冻结 + 释放预留 =================

    @Test
    void freezeReleasesReservation() {
        Map<String, Object> o = traceService.analyze("BATCH", "TRC-B5", "冻结联动测试");
        login("qeng-trc", "ROLE_QUALITY_ENG");   // 质量冻结发起角色（4.9 分流）
        Map<String, Object> r = traceService.freezeStock(String.valueOf(o.get("id")));
        assertEquals(1, r.get("frozenWarehouses"));

        // 冻结记录 reason 关联 TR 单号
        String reason = jdbc.queryForObject(
                "SELECT REASON FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ? AND BATCH_NO = ?",
                String.class, WH, "TRC-B5");
        assertTrue(reason.contains(String.valueOf(o.get("traceNo"))), reason);
        // 预留释放（BR-4.4-49）
        String rvStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_sd_reservation WHERE ID = 'trc-rv-5'", String.class);
        assertEquals("RELEASED", rvStatus);
        // 行状态 FROZEN + 审计
        Map<String, Object> d = traceService.detail(String.valueOf(o.get("id")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flows = (List<Map<String, Object>>) d.get("flows");
        assertTrue(flows.stream()
                .filter(f -> "STOCK_AVAILABLE".equals(f.get("flowType")))
                .allMatch(f -> "FROZEN".equals(f.get("status"))), "在库可用行应 FROZEN");
        assertFalse(flows.stream()
                .filter(f -> "STOCK_AVAILABLE".equals(f.get("flowType"))).findFirst().isEmpty());
        assertTrue(traceService.auditLogs(String.valueOf(o.get("id"))).stream()
                .anyMatch(l -> "FREEZE".equals(l.get("action"))));
        // 重复执行 → 422 无可执行行
        ServiceException again = assertThrows(ServiceException.class,
                () -> traceService.freezeStock(String.valueOf(o.get("id"))));
        assertEquals(422, again.getCode());
    }

    // ================= 10.1 拦截升级与应召定格 =================

    @Test
    void interceptUpgradeAndReturnImmutable() {
        Map<String, Object> o = traceService.analyze("BATCH", "TRC-B1", "拦截召回测试");
        Map<String, Object> d = traceService.detail(String.valueOf(o.get("id")));

        String inTransit = flowIdOf(d, "IN_TRANSIT", "TRC-TO-1");
        String unsigned = flowIdOf(d, "OUT_UNSIGNED", "SH-TRC-1");
        String signed1 = flowIdOf(d, "OUT_SIGNED", "SH-TRC-2");
        String signed2 = flowIdOf(d, "OUT_SIGNED", "SH-TRC-3");

        // 拦截成功 → INTERCEPTED
        traceService.registerIntercept(inTransit, true);
        assertEquals("INTERCEPTED", statusOf(inTransit));

        // 拦截失败 → 原行 FAILED + 升级出 OUT_SIGNED 召回行（应召=原在途数量）
        Map<String, Object> fail = traceService.registerIntercept(unsigned, false);
        assertEquals("FAILED", fail.get("status"));
        String upId = String.valueOf(fail.get("upgradedFlowId"));
        assertEquals("OUT_SIGNED",
                jdbc.queryForObject("SELECT FLOW_TYPE FROM erp_inv_trace_flow WHERE ID = ?",
                        String.class, upId));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject(
                "SELECT EXPECT_QTY FROM erp_inv_trace_flow WHERE ID = ?", BigDecimal.class, upId)),
                "升级行应召 = 原在途数量");

        // 应召定格：实退 50 > 应召 40 → 422
        ServiceException over = assertThrows(ServiceException.class,
                () -> traceService.registerReturn(signed1, new BigDecimal("50"), null, null));
        assertTrue(over.getMessage().contains("应召"), over.getMessage());
        // 实退 30 → RECEIVED
        traceService.registerReturn(signed1, new BigDecimal("30"), "TRC-B1", null);
        assertEquals("RECEIVED", statusOf(signed1));
        // 拒退留痕 → REJECTED
        traceService.registerReturn(signed2, null, null, "已消耗无法退回");
        assertEquals("REJECTED", statusOf(signed2));
        // 重复登记 → 422
        ServiceException dup = assertThrows(ServiceException.class,
                () -> traceService.registerReturn(signed2, BigDecimal.ONE, null, null));
        assertEquals(422, dup.getCode());
    }

    // ================= 7.3 + 结案召回率 + 解冻拦截 =================

    @Test
    void closeRateAndUnfreezeIntercept() {
        Map<String, Object> o = traceService.analyze("BATCH", "TRC-B6", "结案测试");
        login("qeng-trc", "ROLE_QUALITY_ENG");
        traceService.freezeStock(String.valueOf(o.get("id")));
        // 处置回调（报废链 post 后同事务调用——此处直调模拟；真实链路在 receiveAndScrap 测试）
        recallService.markDisposed(String.valueOf(o.get("traceNo")), Set.of("TRC-B6"));
        String signed = null;
        Map<String, Object> d = traceService.detail(String.valueOf(o.get("id")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flows = (List<Map<String, Object>>) d.get("flows");
        for (Map<String, Object> f : flows) {
            if ("OUT_SIGNED".equals(f.get("flowType"))) {
                signed = String.valueOf(f.get("id"));
            }
        }
        assertNotNull(signed);

        // 数据缺口行（待人工核查）先登记拦截闭环——结案前置（spec 流向缺口语义）
        traceService.registerIntercept(firstFlowId(String.valueOf(o.get("id")),
                "OUT_UNSIGNED"), true);

        // 生效冻结行（解冻拦截用）
        jdbc.update("INSERT INTO erp_inv_freeze (ID, FREEZE_NO, FREEZE_TYPE, WAREHOUSE_CODE, "
                + "ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, REASON, SCOPE, STATUS, SOURCE, "
                + "APPLY_BY, CREATE_BY) VALUES ('trc-fz-1', 'FZ-TRC-1', 'QUALITY', ?, ?, "
                + "'追溯召回测试物料', 'TRC-B6', 100, '测试冻结', 'BATCH', 'ACTIVE', 'MANUAL', "
                + "'tr-freezer', 'junit')", WH, ITEM);

        // 未闭环（已签收行 PENDING）→ 结案 422 展示清单
        login("qmgr-trc", "ROLE_QUALITY_MGR");
        ServiceException open = assertThrows(ServiceException.class,
                () -> traceService.close(String.valueOf(o.get("id"))));
        assertEquals(422, open.getCode());
        assertTrue(open.getMessage().contains("未闭环"), open.getMessage());

        // 未结案 → 解冻拦截（C-4.4-07）
        login("tr-freezer", "ROLE_ADMIN");
        ServiceException block = assertThrows(ServiceException.class,
                () -> freezeService.applyUnfreeze("trc-fz-1", "处理完成", "批次复查通过"));
        assertTrue(block.getMessage().contains("召回未结案"), block.getMessage());

        // 实退 90/应召 100 → 结案召回率 90% + 未召回逐笔
        login("qeng-trc", "ROLE_QUALITY_ENG");
        traceService.registerReturn(signed, new BigDecimal("90"), "TRC-B6", null);
        login("qmgr-trc", "ROLE_QUALITY_MGR");
        Map<String, Object> closed = traceService.close(String.valueOf(o.get("id")));
        assertEquals("CLOSED", closed.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> report = (Map<String, Object>) closed.get("report");
        assertEquals(0, new BigDecimal("90").compareTo((BigDecimal) report.get("recallRate")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> un = (List<Map<String, Object>>) report.get("unreturned");
        assertEquals(1, un.size());
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) un.get(0).get("shortQty")));

        // 结案后解冻放行
        login("tr-freezer", "ROLE_ADMIN");
        var f = freezeService.applyUnfreeze("trc-fz-1", "处理完成", "批次复查通过");
        assertNotNull(f.getUnfreezeApprId(), "结案后应正常创建解冻审批");
    }

    // ================= 6.3 受限区入库 + 报废 qcFirst 处置闭环 =================

    @Test
    void receiveRestrictedAndScrapQcFirst() {
        Map<String, Object> o = traceService.analyze("BATCH", "TRC-B7", "处置闭环测试");
        login("qeng-trc", "ROLE_QUALITY_ENG");
        traceService.freezeStock(String.valueOf(o.get("id")));
        String flowId = firstFlowId(String.valueOf(o.get("id")), "STOCK_AVAILABLE");

        // 动作级分权（10.2）：QUALITY_ENG 入库 → 403
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> recallService.receive(String.valueOf(o.get("id")), flowId, WH,
                        BIN_SCRAP, new BigDecimal("30")));
        assertEquals(403, forbidden.getCode());

        login("wh-trc", "ROLE_WAREHOUSE");
        // 入 STORE 区 → 422（BR-4.4-50 L1），无库存变动
        ServiceException store = assertThrows(ServiceException.class,
                () -> recallService.receive(String.valueOf(o.get("id")), flowId, WH,
                        BIN_STORE, new BigDecimal("30")));
        assertTrue(store.getMessage().contains("残次品区/退货区"), store.getMessage());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_stock WHERE BIN_CODE = ?", Integer.class, BIN_SCRAP),
                "422 后不应有受限区库存");

        // 入 SCRAP 区 → QC 列 +30，AVAILABLE 不变
        Map<String, Object> recv = recallService.receive(String.valueOf(o.get("id")), flowId,
                WH, BIN_SCRAP, new BigDecimal("30"));
        assertEquals("SCRAP", recv.get("binType"));
        assertEquals(0, new BigDecimal("30").compareTo(stockCol(BIN_SCRAP, "TRC-B7", "QC_QTY")));
        assertEquals(0, new BigDecimal("30").compareTo(stockCol(BIN_SCRAP, "TRC-B7", "QTY")));

        // 报废携带 TR 单号：QC 30 + 可用 10 = 40（qcFirst 语义）
        InvScrapOrder head = new InvScrapOrder();
        head.setReason(InvScrapOrder.R_OTHER);
        head.setWarehouseCode(WH);
        head.setTraceNo(String.valueOf(o.get("traceNo")));
        head.setRemark("召回处置报废");
        InvScrapOrderLine line = new InvScrapOrderLine();
        line.setItemCode(ITEM);
        line.setItemName("追溯召回测试物料");
        line.setBatchNo("TRC-B7");
        line.setQty(new BigDecimal("40"));
        line.setUnitCost(new BigDecimal("5"));
        Map<String, Object> created = scrapOrderService.create(head, List.of(line));
        String scrapId = scrapOrderId(String.valueOf(o.get("traceNo")));
        scrapOrderService.approve(scrapId);
        scrapOrderService.post(scrapId);
        assertNotNull(created);

        // qcFirst：先核销 SCRAP 位 QC 30（QTY 归 0），可用 50 → 40
        assertEquals(0, BigDecimal.ZERO.compareTo(stockCol(BIN_SCRAP, "TRC-B7", "QC_QTY")), "QC 应核销为 0");
        assertEquals(0, BigDecimal.ZERO.compareTo(stockCol(BIN_SCRAP, "TRC-B7", "QTY")));
        assertEquals(0, new BigDecimal("40").compareTo(stockCol(BIN_STORE, "TRC-B7", "AVAILABLE_QTY")));
        // 处置回调 → 流向行 DISPOSED
        assertEquals("DISPOSED", statusOf(flowId));
        // 报废单头 TRACE_NO 持久化
        assertEquals(o.get("traceNo"), jdbc.queryForObject(
                "SELECT TRACE_NO FROM erp_inv_scrap_order WHERE ID = ?", String.class, scrapId));
        // 审计：RECEIVE + DISPOSE
        var logs = traceService.auditLogs(String.valueOf(o.get("id")));
        assertTrue(logs.stream().anyMatch(l -> "RECEIVE".equals(l.get("action"))));
        assertTrue(logs.stream().anyMatch(l -> "DISPOSE".equals(l.get("action"))));

        // QC+可用合计不足 → 引擎 422 阻断（无流水残留）
        jdbc.update("UPDATE erp_inv_stock SET QC_QTY = 30, AVAILABLE_QTY = 5 "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BIN_CODE = ?",
                WH, ITEM, BIN_STORE);
        InvScrapOrder head2 = new InvScrapOrder();
        head2.setReason(InvScrapOrder.R_OTHER);
        head2.setWarehouseCode(WH);
        head2.setRemark("QC不足阻断测试");
        InvScrapOrderLine line2 = new InvScrapOrderLine();
        line2.setItemCode(ITEM);
        line2.setItemName("追溯召回测试物料");
        line2.setBatchNo("TRC-B7");
        line2.setQty(new BigDecimal("38"));   // onHand 45 过创建校验；QC30+可用5=35 < 38 → 引擎拦
        line2.setUnitCost(new BigDecimal("5"));
        scrapOrderService.create(head2, List.of(line2));
        String scrapId2 = jdbc.queryForObject(
                "SELECT ID FROM erp_inv_scrap_order WHERE WAREHOUSE_CODE = ? AND REMARK = ?",
                String.class, WH, "QC不足阻断测试");
        scrapOrderService.approve(scrapId2);
        ServiceException short2 = assertThrows(ServiceException.class,
                () -> scrapOrderService.post(scrapId2));
        assertEquals(422, short2.getCode());
        assertEquals(0, new BigDecimal("5").compareTo(stockCol(BIN_STORE, "TRC-B7", "AVAILABLE_QTY")),
                "失败后可用量未动（仍为改数后的 5）");
        assertEquals(0, new BigDecimal("30").compareTo(stockCol(BIN_STORE, "TRC-B7", "QC_QTY")),
                "失败后 QC 列未动");
    }

    // ---------- helpers ----------

    private void seedBin(String id, String bin, String type) {
        jdbc.update("INSERT INTO erp_inv_bin (ID, WH_CODE, ZONE_CODE, BIN_CODE, BIN_SEQ, "
                + "COL_NO, LAYER_NO, BIN_TYPE, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'TC', ?, 1, 1, 1, ?, '1', 'junit', '0', 0)", id, WH, bin, type);
    }

    private void seedBatch(String id, String batch, String supplierBatch) {
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                + "PRODUCTION_DATE, EXPIRY_DATE, SUPPLIER_BATCH_NO, EXPIRY_LOCK_FLAG, STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, '追溯召回测试物料', ?, ?, ?, '0', "
                + "'1', 'junit', '0', 0)", id, batch, ITEM,
                LocalDate.now().minusDays(60).toString(),
                LocalDate.now().plusDays(300).toString(), supplierBatch);
    }

    private void seedStock(String id, String batch, String bin, String qty, String qc, String avail) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                + "BATCH_NO, BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, "
                + "CREATE_BY) VALUES (?, ?, ?, '追溯召回测试物料', ?, ?, ?, ?, 0, ?, "
                + "'2026-10-01', 'junit')", id, WH, ITEM, batch, bin, qty, qc, avail);
    }

    private void seedShipment(String id, String shipNo, String status, String signAt,
                              String custCode, String custName, boolean signed) {
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, "
                + "CUSTOMER_CODE, CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, "
                + "SIGN_AT, CREATE_BY) VALUES (?, ?, 'NORMAL', ?, ?, ?, ?, ?, 0, ?, 'junit')",
                id, shipNo, custCode, custCode, custName, WH, status, signAt);
    }

    private void seedShipmentLine(String id, String shipId, String batch, int qty) {
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, SO_LINE_ID, "
                + "SO_NO, SO_LINE_NO, ITEM_CODE, ITEM_NAME, QTY, WAREHOUSE_CODE, BATCH_NO, "
                + "LINE_STATUS, CREATE_BY) VALUES (?, ?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, "
                + "'POSTED', 'junit')", id, shipId, shipId + "-so", shipId + "-so-L1",
                shipId + "-SONO", ITEM, "追溯召回测试物料", qty, WH, batch);
    }

    @SuppressWarnings("unchecked")
    private String flowIdOf(Map<String, Object> detail, String flowType, String srcDocNo) {
        List<Map<String, Object>> flows = (List<Map<String, Object>>) detail.get("flows");
        for (Map<String, Object> f : flows) {
            if (flowType.equals(f.get("flowType")) && srcDocNo.equals(f.get("srcDocNo"))) {
                return String.valueOf(f.get("id"));
            }
        }
        throw new AssertionError("flow not found: " + flowType + "/" + srcDocNo);
    }

    private String firstFlowId(String traceId, String flowType) {
        return jdbc.queryForObject("SELECT ID FROM erp_inv_trace_flow WHERE TRACE_ID = ? "
                + "AND FLOW_TYPE = ? LIMIT 1", String.class, traceId, flowType);
    }

    private String statusOf(String flowId) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_inv_trace_flow WHERE ID = ?",
                String.class, flowId);
    }

    private BigDecimal stockCol(String bin, String batch, String col) {
        BigDecimal v = jdbc.query("SELECT " + col + " FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? "
                + "AND ITEM_CODE = ? AND BATCH_NO = ? AND BIN_CODE = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, WH, ITEM, batch, bin);
        return v == null ? BigDecimal.ZERO : v;
    }

    private String scrapOrderId(String traceNo) {
        return jdbc.queryForObject("SELECT ID FROM erp_inv_scrap_order WHERE TRACE_NO = ?",
                String.class, traceNo);
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }
}
