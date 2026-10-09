package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvWave;
import com.erp.entity.inv.InvWaveDoc;
import com.erp.entity.inv.InvWaveLine;
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
 * 波次分配 DB 集成（真实 MySQL，spec wave-management 波次级统一分配，任务 4.1/4.2）：
 * 共享预算防误拆（自身预留不占预算）、按创建序先到先得切分、
 * 不足拆单（BR-4.4-47）、双写回发货行、确认分配前置与锁行拦截（C-4.4-08）。
 */
@SpringBootTest
class WaveAllocateDbTest {

    private static final String WH = "WH-WVAL";
    private static final String ITEM = "IT-WVA-01";
    private static final String RT = "wva-route";

    @Autowired
    private WaveService service;
    @Autowired
    private JdbcTemplate jdbc;

    private String waveId;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));

        jdbc.update("INSERT INTO erp_inv_route (ID, ROUTE_CODE, ROUTE_NAME, STATUS, CREATE_BY) "
                + "VALUES (?, 'WV-AR1', '分配测试线', 'ACTIVE', 'junit')", RT);
        // 物料 + 库存：B1 80（早入库）、B2 60（晚入库）
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, STATUS, CREATE_BY) "
                + "VALUES ('wva-it', ?, '分配测试物料', '0001', 'PC', 'STRUCT', 'BUY', 'NORMAL', '1', "
                + "'1', 'junit')", ITEM);
        insertStock("wva-st1", "WVA-B1", "BIN-A01", 80, "2026-09-01");
        insertStock("wva-st2", "WVA-B2", "BIN-A02", 60, "2026-10-01");
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                + "('wva-lg1','WVA-B1',?, '分配测试物料','2026-08-01','2027-12-31','0','1','junit'), "
                + "('wva-lg2','WVA-B2',?, '分配测试物料','2026-09-01','2027-12-31','0','1','junit')",
                ITEM, ITEM);

        // 两张 DRAFT 发货单（同线路）：A 需求 60（B1 就够），B 需求 70（B1 剩 20 + B2 50）
        insertShipment("wva-sh-a", "SH-WVA-A", "wva-so-a");
        insertLine("wva-sol-a", "wva-sh-a", 1, 60, "wva-so-a");
        insertShipment("wva-sh-b", "SH-WVA-B", "wva-so-b");
        insertLine("wva-sol-b", "wva-sh-b", 1, 70, "wva-so-b");
        // A 单 ATP 预留 60（B1）——共享预算必须排除自身，否则 B1 预算=80-60=20 会误拆
        jdbc.update("INSERT INTO erp_sd_reservation (ID, SO_ID, SO_NO, LINE_ID, LINE_NO, "
                + "ITEM_CODE, WAREHOUSE_CODE, BATCH_NO, QTY, STATUS, LOCK_AT, CREATE_BY) VALUES "
                + "('wva-rsv-a','wva-so-a','SO-WVA-A','wva-sol-a',1,?,'WH-MAIN','WVA-B1',60,"
                + "'ACTIVE',NOW(),'junit')", ITEM);

        // 建波次并绑定两单
        jdbc.update("INSERT INTO erp_inv_wave (ID, WAVE_NO, CLUSTER_TYPE, CLUSTER_KEY, STATUS, "
                + "DOC_COUNT, CREATE_BY) VALUES ('wva-wave','WVTEST0001','ROUTE',?,'CREATED',2,"
                + "'junit')", RT);
        waveId = "wva-wave";
        jdbc.update("INSERT INTO erp_inv_wave_doc (ID, WAVE_ID, SHIP_ID, SHIP_NO, BIND_STATUS, "
                + "SORT_STATUS, LOAD_STATUS, CREATE_BY) VALUES "
                + "('wva-doc-a','wva-wave','wva-sh-a','SH-WVA-A','BOUND','PENDING','PENDING','junit'), "
                + "('wva-doc-b','wva-wave','wva-sh-b','SH-WVA-B','BOUND','PENDING','PENDING','junit')");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    @Test
    void sharedBudgetExcludesOwnReservationAndAllocatesInOrder() {
        // 普通口径下 B1 预算 = 80−60=20，波次需求 130 会被误判；波次口径 B1=80、B2=60 → 140≥130
        Map<String, Object> out = service.allocate(waveId);
        assertEquals(0, ((Number) out.get("unboundDocs")).intValue(), "共享预算防误拆：无单被拆出");
        assertTrue(((Number) out.get("allocatedLines")).intValue() >= 2, "分配行落库");

        // 先到先得（单号序）：A 全走 B1 60；B 走 B1 剩 20 + B2 50
        Map<String, Object> segA = jdbc.queryForMap(
                "SELECT BATCH_NO, QTY FROM erp_inv_wave_line WHERE SHIP_LINE_ID = 'wva-sol-a'");
        assertEquals("WVA-B1", segA.get("BATCH_NO"), "A 单先切最早批次");
        assertEquals(0, new BigDecimal("60").compareTo(toDec(segA.get("QTY"))));

        List<Map<String, Object>> segsB = jdbc.queryForList(
                "SELECT BATCH_NO, QTY FROM erp_inv_wave_line WHERE SHIP_LINE_ID = 'wva-sol-b' "
                        + "ORDER BY CREATE_DATE, ID");
        assertEquals(2, segsB.size(), "B 单跨两批拆段");
        BigDecimal b1 = BigDecimal.ZERO;
        BigDecimal b2 = BigDecimal.ZERO;
        for (Map<String, Object> m : segsB) {
            if ("WVA-B1".equals(m.get("BATCH_NO"))) {
                b1 = b1.add(toDec(m.get("QTY")));
            } else {
                b2 = b2.add(toDec(m.get("QTY")));
            }
        }
        assertEquals(0, new BigDecimal("20").compareTo(b1), "B1 剩余 80−60=20 归 B 单");
        assertEquals(0, new BigDecimal("50").compareTo(b2), "B2 补足 50");

        // 双写发货行（首段回写）
        String shipBatch = jdbc.queryForObject(
                "SELECT BATCH_NO FROM erp_sd_shipment_line WHERE ID = 'wva-sol-a'",
                String.class);
        assertEquals("WVA-B1", shipBatch, "分配首段回写发货行");
    }

    @Test
    void insufficientDocsUnboundAndAllUnboundCancelsWave() {
        // 把库存砍到只剩 30（B1）→ A(60)+B(70) 全部不足 → 全拆 → 波次 CANCELLED
        jdbc.update("UPDATE erp_inv_stock SET QTY = 20, AVAILABLE_QTY = 20 WHERE ID = 'wva-st1'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID = 'wva-st2'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ID = 'wva-lg2'");
        jdbc.update("UPDATE erp_sd_reservation SET QTY = 0, STATUS = 'RELEASED' "
                + "WHERE ID = 'wva-rsv-a'");

        Map<String, Object> out = service.allocate(waveId);
        assertEquals(Boolean.TRUE, out.get("allUnbound"), "全部拆出 → 波次取消");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave WHERE ID = ?",
                String.class, waveId);
        assertEquals(InvWave.ST_CANCELLED, st, "spec：全部订单被拆出时波次 CANCELLED");
        int bound = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_wave_doc "
                + "WHERE WAVE_ID = ? AND BIND_STATUS = 'BOUND'", Integer.class, waveId);
        assertEquals(0, bound, "全部解绑（行保留）");
    }

    @Test
    void partialShortUnbindsOnlyShortDoc() {
        // 库存 B1=70、无 B2 → A(60) 先到全拿，B(70) 只剩 10 → B 拆出
        jdbc.update("UPDATE erp_inv_stock SET QTY = 70, AVAILABLE_QTY = 70 WHERE ID = 'wva-st1'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID = 'wva-st2'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ID = 'wva-lg2'");

        Map<String, Object> out = service.allocate(waveId);
        assertEquals(1, ((Number) out.get("unboundDocs")).intValue(), "仅 B 单拆出");
        String bBind = jdbc.queryForObject("SELECT BIND_STATUS FROM erp_inv_wave_doc "
                + "WHERE ID = 'wva-doc-b'", String.class);
        assertEquals(InvWaveDoc.BIND_UNBOUND, bBind, "B 单解绑转分批出库");
        String aBind = jdbc.queryForObject("SELECT BIND_STATUS FROM erp_inv_wave_doc "
                + "WHERE ID = 'wva-doc-a'", String.class);
        assertEquals(InvWaveDoc.BIND_BOUND, aBind, "A 单保留");
        String aSeg = jdbc.queryForObject("SELECT BATCH_NO FROM erp_inv_wave_line "
                + "WHERE SHIP_LINE_ID = 'wva-sol-a'", String.class);
        assertEquals("WVA-B1", aSeg, "A 单分配照常");
        // 状态仍 CREATED（只计算落行，不改状态）
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave WHERE ID = ?",
                String.class, waveId);
        assertEquals(InvWave.ST_CREATED, st, "allocate 不改状态");
    }

    @Test
    void confirmAllocateBlockedByLockAndRequiresPlan() {
        // 未分配 → 确认 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.confirmAllocate(waveId));
        assertEquals(422, ex.getCode());

        service.allocate(waveId);
        // 预置锁行（模拟改批审批中）
        jdbc.update("UPDATE erp_inv_wave_line SET LOCK_FLAG = '1' WHERE WAVE_ID = ?", waveId);
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> service.confirmAllocate(waveId));
        assertEquals(422, ex2.getCode(), "C-4.4-08：LOCKED 未解锁不可确认分配");

        // 解锁 → 确认成功 → ALLOCATED
        jdbc.update("UPDATE erp_inv_wave_line SET LOCK_FLAG = '0' WHERE WAVE_ID = ?", waveId);
        service.confirmAllocate(waveId);
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave WHERE ID = ?",
                String.class, waveId);
        assertEquals(InvWave.ST_ALLOCATED, st, "确认后 ALLOCATED");
        // 分配期间再次计算 422（非 CREATED）
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> service.allocate(waveId));
        assertEquals(422, ex3.getCode());
    }

    // ---------- 夹具 ----------

    private void insertStock(String id, String batch, String bin, int qty, String inbound) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                + "BATCH_NO, BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, "
                + "CREATE_BY) VALUES (?, ?, ?, '分配测试物料', ?, ?, ?, 0, 0, ?, ?, 'junit')",
                id, WH, ITEM, batch, bin, qty, qty, inbound);
    }

    private void insertShipment(String id, String shipNo, String soId) {
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, "
                + "CUSTOMER_CODE, CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, TOTAL_AMT, "
                + "ROUTE_ID, CREATE_BY) VALUES (?, ?, 'PARTIAL', ?, 'C-WVA', '分配测试客户', ?, "
                + "'DRAFT', 0, 0, ?, 'junit')", id, shipNo, soId, WH, RT);
    }

    private void insertLine(String id, String shipId, int lineNo, int qty, String soId) {
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, SO_LINE_ID, "
                + "SO_NO, SO_LINE_NO, ITEM_CODE, ITEM_NAME, QTY, WAREHOUSE_CODE, LINE_STATUS, "
                + "CREATE_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '分配测试物料', ?, ?, 'PENDING', "
                + "'junit')",
                id, shipId, lineNo, soId, soId + "-L1", "SO-" + soId, lineNo, ITEM, qty, WH);
    }

    private static BigDecimal toDec(Object o) {
        return o instanceof BigDecimal d ? d : new BigDecimal(String.valueOf(o));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_wave_line WHERE WAVE_ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_inv_wave_doc WHERE WAVE_ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_inv_wave_adjust WHERE WAVE_ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_inv_wave WHERE ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_sd_reservation WHERE ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ID LIKE 'wva-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ID = 'wva-it'");
        jdbc.update("DELETE FROM erp_inv_route WHERE ID = ?", RT);
    }
}
