package com.erp.inv;

import com.erp.dao.inv.InvCheckReportDao;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvCheckReport;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Reservation;
import com.erp.service.inv.StockSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 库存快照查询 DB 侧语义（真实 MySQL，spec stock-snapshot，任务 3.2~3.5）：
 * 四列口径（100/20/30→50 与双类叠加）、预留下钻、冻结下钻、
 * 待核实标记（最近一次校验报告）、在制恒空、未登录 401。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StockSnapshotDbTest {

    private static final String WH = "WH-JT";
    private static final String ITEM = "IT-SNAP-TEST";

    @Autowired
    private StockSnapshotService service;
    @Autowired
    private InvStockDao stockDao;
    @Autowired
    private ReservationDao reservationDao;
    @Autowired
    private InvFreezeDao freezeDao;
    @Autowired
    private InvCheckReportDao checkReportDao;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> cleanupIds = new java.util.ArrayList<>();

    @BeforeEach
    void purgeResidue() {
        // 防御自清：历史轮次 @TableLogic 软删行占用库存 UK / 报告 RUN_DATE UK，物理清除
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_check_report WHERE RUN_DATE = '2999-01-01'");
    }

    @AfterEach
    void cleanup() {
        for (String id : cleanupIds) {
            stockDao.deleteById(id);
            reservationDao.deleteById(id);
            freezeDao.deleteById(id);
            checkReportDao.deleteById(id);
        }
        cleanupIds.clear();
    }

    private InvStock stock(String suffix, String qty, String qc, String fin) {
        InvStock s = new InvStock();
        s.setId("junit-snap-" + suffix + "-" + System.nanoTime());
        cleanupIds.add(s.getId());
        s.setWarehouseCode(WH);
        s.setItemCode(ITEM);
        s.setItemName("快照测试物料");
        s.setBatchNo("B-JT-" + suffix);
        s.setQty(new BigDecimal(qty));
        s.setQcQty(new BigDecimal(qc));
        s.setFinQty(new BigDecimal(fin));
        s.setAvailableQty(new BigDecimal(qty).subtract(new BigDecimal(qc)).subtract(new BigDecimal(fin)));
        stockDao.insert(s);
        return s;
    }

    private Map<String, Object> rowOf(Map<String, Object> result, String batchNo) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        return rows.stream()
                .filter(r -> batchNo.equals(r.get("batchNo")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到批次行 " + batchNo));
    }

    /** 3.2 四列口径：100 / 冻结 20 / 预留 30 → 可用 50（FR-4.4-2-2） */
    @Test
    void availableFormulaWithReserved() {
        stock("A", "100", "20", "0");
        Reservation r = new Reservation();
        r.setId("junit-res-" + System.nanoTime());
        cleanupIds.add(r.getId());
        r.setSoId("SO-JT");
        r.setSoNo("SO-JT-001");
        r.setLineId("SL-JT-1");
        r.setLineNo(1);
        r.setItemCode(ITEM);
        r.setWarehouseCode(WH);
        r.setBatchNo("B-JT-A");
        r.setQty(new BigDecimal("30"));
        r.setStatus(Reservation.ST_ACTIVE);
        r.setLockAt(LocalDateTime.now());
        reservationDao.insert(r);

        Map<String, Object> result = service.query(WH, ITEM, null, null);
        Map<String, Object> row = rowOf(result, "B-JT-A");
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) row.get("qty")));
        assertEquals(0, new BigDecimal("20").compareTo((BigDecimal) row.get("frozen")));
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) row.get("reserved")));
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) row.get("available")),
                "可用 = 100 − 20 − 30 = 50");

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.get("totals");
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) totals.get("available")));
        assertTrue(result.get("asOf") != null, "须带数据截至时间戳");
    }

    /** 3.2 双类冻结叠加：100 / (10+15) → 冻结 25、可用 75（BR-4.4-14） */
    @Test
    void dualFreezeStacking() {
        stock("B", "100", "10", "15");
        Map<String, Object> result = service.query(WH, ITEM, null, null);
        Map<String, Object> row = rowOf(result, "B-JT-B");
        assertEquals(0, new BigDecimal("25").compareTo((BigDecimal) row.get("frozen")),
                "质量 10 + 财务 15 = 25");
        assertEquals(0, new BigDecimal("75").compareTo((BigDecimal) row.get("available")));
    }

    /** 3.3 预留下钻与冻结下钻（明细与主表汇总同源） */
    @Test
    void detailDrilldowns() {
        stock("C", "50", "0", "0");
        Reservation r = new Reservation();
        r.setId("junit-res-" + System.nanoTime());
        cleanupIds.add(r.getId());
        r.setSoId("SO-JT2");
        r.setSoNo("SO-JT-002");
        r.setLineId("SL-JT-2");
        r.setLineNo(2);
        r.setItemCode(ITEM);
        r.setWarehouseCode(WH);
        r.setBatchNo("B-JT-C");
        r.setQty(new BigDecimal("12"));
        r.setStatus(Reservation.ST_ACTIVE);
        r.setLockAt(LocalDateTime.now().minusHours(1));
        reservationDao.insert(r);

        InvFreeze f = new InvFreeze();
        f.setId("junit-freeze-" + System.nanoTime());
        cleanupIds.add(f.getId());
        f.setFreezeNo("FZ-JT-" + System.nanoTime() % 100000);
        f.setFreezeType(InvFreeze.T_FINANCE);
        f.setWarehouseCode(WH);
        f.setItemCode(ITEM);
        f.setBatchNo("B-JT-C");
        f.setQty(new BigDecimal("5"));
        f.setReason("审计封存");
        f.setScope(InvFreeze.SCOPE_BATCH);
        f.setStatus(InvFreeze.ST_ACTIVE);
        f.setSource(InvFreeze.SRC_MANUAL);
        f.setApplyBy("junit");
        freezeDao.insert(f);

        // 预留下钻：与主表 reserved 同源
        List<Map<String, Object>> res = service.reservedDetail(WH, ITEM, "B-JT-C");
        assertEquals(1, res.size());
        assertEquals("SO-JT-002", res.get(0).get("soNo"));
        assertEquals(0, new BigDecimal("12").compareTo((BigDecimal) res.get(0).get("qty")));

        Map<String, Object> q = service.query(WH, ITEM, "B-JT-C", null);
        assertEquals(0, new BigDecimal("12").compareTo(
                (BigDecimal) rowOf(q, "B-JT-C").get("reserved")), "下钻明细与主表汇总一致");

        // 冻结下钻：仅 ACTIVE 台账
        List<Map<String, Object>> frz = service.freezeDetail(WH, ITEM, "B-JT-C");
        assertEquals(1, frz.size());
        assertEquals("FINANCE", frz.get(0).get("freezeType"));
        assertEquals("审计封存", frz.get(0).get("reason"));
    }

    /** 3.4 待核实标记：最近一次校验报告的差异维度 → 行级 pendingVerify（BR-4.4-15） */
    @Test
    void pendingVerifyFlagFromLatestReport() {
        InvStock a = stock("D", "100", "0", "0");
        stock("E", "60", "0", "0");

        InvCheckReport rep = new InvCheckReport();
        rep.setId("junit-check-" + System.nanoTime());
        cleanupIds.add(rep.getId());
        // 远期运行日保证是「最近一次」，避免与真实调度撞 RUN_DATE 唯一键
        rep.setRunDate(LocalDate.of(2999, 1, 1));
        rep.setMismatchCnt(1);
        rep.setStatus(InvCheckReport.ST_MISMATCH);
        rep.setDetailJson("[{\"warehouseCode\":\"" + WH + "\",\"itemCode\":\"" + ITEM
                + "\",\"batchNo\":\"B-JT-D\",\"qty\":100}]");
        checkReportDao.insert(rep);

        Map<String, Object> result = service.query(WH, ITEM, null, null);
        assertTrue((Boolean) rowOf(result, "B-JT-D").get("pendingVerify"), "差异行须标待核实");
        assertFalse((Boolean) rowOf(result, "B-JT-E").get("pendingVerify"), "非差异行不标");

        Map<String, Object> report = service.latestCheckReport();
        assertTrue((Boolean) report.get("exists"));
        assertEquals("MISMATCH", report.get("status"));
    }

    /** 3.5 在制恒空 + 未登录 401 */
    @Test
    void wipEmptyAndUnauthenticated401() throws Exception {
        Map<String, Object> wip = service.wipQuery();
        @SuppressWarnings("unchecked")
        List<?> rows = (List<?>) wip.get("rows");
        assertTrue(rows.isEmpty(), "工单域未建 → 恒空（偏差 D5）");

        mockMvc.perform(get("/api/inv/stock-snapshot")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/inv/wip-stock")).andExpect(status().isUnauthorized());
    }

    // ================= change add-bin-assignment 任务 8.1/8.2 =================

    private void stockBin(String suffix, String bin, String qty) {
        InvStock s = new InvStock();
        s.setId("junit-snapb-" + suffix + "-" + bin + "-" + System.nanoTime());
        cleanupIds.add(s.getId());
        s.setWarehouseCode(WH);
        s.setItemCode(ITEM);
        s.setItemName("快照测试物料");
        s.setBatchNo("B-JT-" + suffix);
        s.setBinCode(bin);
        s.setQty(new BigDecimal(qty));
        s.setQcQty(BigDecimal.ZERO);
        s.setFinQty(BigDecimal.ZERO);
        s.setAvailableQty(new BigDecimal(qty));
        s.setInboundDate(LocalDate.now());
        stockDao.insert(s);
    }

    /** 8.1 位行明细 + 批次合计：同批次跨两仓位，预留批次级只计一次（spec MODIFIED 场景） */
    @Test
    void multiBinRowsAndBatchTotalsCountReservedOnce() {
        stockBin("F", "FA-01-01-01", "60");
        stockBin("F", "FC-01-01-01", "40");
        Reservation r = new Reservation();
        r.setId("junit-res-" + System.nanoTime());
        cleanupIds.add(r.getId());
        r.setSoId("SO-JT3");
        r.setSoNo("SO-JT-003");
        r.setLineId("SL-JT-3");
        r.setLineNo(3);
        r.setItemCode(ITEM);
        r.setWarehouseCode(WH);
        r.setBatchNo("B-JT-F");
        r.setQty(new BigDecimal("30"));
        r.setStatus(Reservation.ST_ACTIVE);
        r.setLockAt(LocalDateTime.now());
        reservationDao.insert(r);

        Map<String, Object> result = service.query(WH, ITEM, "B-JT-F", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertEquals(2, rows.size(), "位行明细两行");
        assertEquals("FA-01-01-01", rows.get(0).get("binCode"));
        assertEquals(0, new BigDecimal("60").compareTo((BigDecimal) rows.get(0).get("qty")));
        assertEquals("FC-01-01-01", rows.get(1).get("binCode"));
        assertEquals(0, new BigDecimal("40").compareTo((BigDecimal) rows.get(1).get("qty")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bt = (List<Map<String, Object>>) result.get("batchTotals");
        assertEquals(1, bt.size(), "批次合计一条");
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) bt.get(0).get("qty")));
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) bt.get(0).get("reserved")));
        assertEquals(0, new BigDecimal("70").compareTo((BigDecimal) bt.get(0).get("available")),
                "批次合计可用 = 100 − 0 − 30 = 70");

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) result.get("totals");
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) totals.get("reserved")),
                "预留不因多仓位行重复计");
        assertEquals(0, new BigDecimal("70").compareTo((BigDecimal) totals.get("available")));
    }

    /** 8.2 日结按位行插入：同批次多仓位不撞唯一键，binCode 落位正确 */
    @Test
    void dayCloseInsertsBinLevelRows() {
        stockBin("G", "GA-01-01-01", "10");
        stockBin("G", "GC-01-01-01", "20");
        LocalDate balDate = LocalDate.now().minusDays(1);
        // 清掉该结算日既有行（日结按日幂等 skip，测试需强制重跑）
        jdbc.update("DELETE FROM erp_inv_daily_balance WHERE BAL_DATE = ?", balDate.toString());
        try {
            Map<String, Object> out = service.runDayClose();
            assertEquals(Boolean.FALSE, out.get("skipped"));
            List<Map<String, Object>> mine = jdbc.queryForList(
                    "SELECT BIN_CODE, QTY FROM erp_inv_daily_balance WHERE BAL_DATE = ? "
                            + "AND ITEM_CODE = ? AND BATCH_NO = 'B-JT-G' ORDER BY BIN_CODE",
                    balDate.toString(), ITEM);
            assertEquals(2, mine.size(), "同批次两位行各一条日结");
            assertEquals("GA-01-01-01", mine.get(0).get("BIN_CODE"));
            assertEquals("GC-01-01-01", mine.get(1).get("BIN_CODE"));
        } finally {
            jdbc.update("DELETE FROM erp_inv_daily_balance WHERE BAL_DATE = ? AND ITEM_CODE = ?",
                    balDate.toString(), ITEM);
        }
    }
}
