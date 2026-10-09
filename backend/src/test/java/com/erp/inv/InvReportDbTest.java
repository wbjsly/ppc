package com.erp.inv;

import com.erp.service.inv.InvReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存报表核心口径（change add-inventory-reports，spec inventory-reports，任务 10.1）：
 * 周转标准口径（快照齐 → SNAPSHOT 精确值）/ 全部出库口径 / 降级（无快照 degraded）/
 * 分母 0 → null（不适用）/ ABC 分组归未分类 / 库龄分桶与呆滞标记、呆滞行清单。
 * 期间用 2020-03 与 2021-01 孤立窗口避免与库内既有流水/快照耦合。
 */
@SpringBootTest
class InvReportDbTest {

    private static final String WH = "WH-RPTG";
    private static final String ITEM_A = "IT-RPTG-A";   // ABC=A，成本 10
    private static final String ITEM_N = "IT-RPTG-N";   // 未分类，成本 5

    @Autowired
    private InvReportService reportService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        cleanup();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STANDARD_COST, ABC_CLASS, STATUS, CREATE_BY) VALUES "
                + "('rptg-it-a', ?, '报表测试A', '0001', 'PCS', 'G001', 'BUY', 'NORMAL', '1', '0', "
                + "10, 'A', '1', 'junit'), ('rptg-it-n', ?, '报表测试N', '0001', 'PCS', 'G001', 'BUY', "
                + "'NORMAL', '1', '0', 5, NULL, '1', 'junit')", ITEM_A, ITEM_N);
        // 当前库存：A 80（库龄 400 天 → 呆滞）+ N 20（库龄 10 天）
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                + "('rptg-stk-a', ?, ?, '报表测试A', 'RPTG-BA', 'BIN-RPTG-1', 80, 0, 0, 80, "
                + "DATE_SUB(CURDATE(), INTERVAL 400 DAY), 'junit'), "
                + "('rptg-stk-n', ?, ?, '报表测试N', 'RPTG-BN', 'BIN-RPTG-2', 20, 0, 0, 20, "
                + "DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'junit')", WH, ITEM_A, WH, ITEM_N);
        // 2020-03 窗口：日结快照两天（金额 100/300 → 均值 200）
        jdbc.update("INSERT INTO erp_inv_daily_balance (ID, BAL_DATE, WAREHOUSE_CODE, ITEM_CODE, "
                + "ITEM_NAME, BATCH_NO, BIN_CODE, QTY, AVAILABLE_QTY, QC_QTY, FIN_QTY, CREATE_BY) VALUES "
                + "('rptg-bal-1', '2020-03-14', ?, ?, '报表测试A', 'RPTG-BA', 'BIN-RPTG-1', 10, 10, 0, 0, 'junit'), "
                + "('rptg-bal-2', '2020-03-15', ?, ?, '报表测试A', 'RPTG-BA', 'BIN-RPTG-1', 30, 30, 0, 0, 'junit')",
                WH, ITEM_A, WH, ITEM_A);
        // 2020-03 流水：SALES_OUT 60（成本 600） + MATERIAL_OUT 40（成本 400）
        insertTxn("rptg-tx-1", "SALES_OUT", ITEM_A, 60, "2020-03-15 10:00:00");
        insertTxn("rptg-tx-2", "MATERIAL_OUT", ITEM_A, 40, "2020-03-16 10:00:00");
        // 2020-04 窗口：有快照但金额为 0（QTY=0）→ 分母 0 → 不适用
        jdbc.update("INSERT INTO erp_inv_daily_balance (ID, BAL_DATE, WAREHOUSE_CODE, ITEM_CODE, "
                + "ITEM_NAME, BATCH_NO, BIN_CODE, QTY, AVAILABLE_QTY, QC_QTY, FIN_QTY, CREATE_BY) VALUES "
                + "('rptg-bal-0', '2020-04-10', ?, ?, '报表测试A', 'RPTG-BA', 'BIN-RPTG-1', 0, 0, 0, 0, 'junit')",
                WH, ITEM_A);
        insertTxn("rptg-tx-3", "SALES_OUT", ITEM_A, 5, "2020-04-11 10:00:00");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_inv_daily_balance WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ID LIKE 'rptg-tx-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE IN (?, ?)", ITEM_A, ITEM_N);
    }

    private void insertTxn(String id, String type, String item, int qty, String at) {
        jdbc.update("INSERT INTO erp_inv_transaction (ID, TXN_NO, DIRECTION, TYPE_CODE, "
                + "BIZ_DOC_TYPE, BIZ_DOC_NO, WAREHOUSE_CODE, ITEM_CODE, BATCH_NO, BIN_CODE, QTY, "
                + "BEFORE_QTY, AFTER_QTY, CREATE_BY, CREATE_DATE) VALUES (?, ?, 'OUT', ?, 'SHIPMENT', "
                + "'RPTG-SO', ?, ?, 'RPTG-BA', 'BIN-RPTG-1', ?, 0, ?, 'junit', ?)",
                id, "TXRPTG" + id.substring(id.length() - 4), type, WH, item, qty, qty, at);
    }

    // ---------- 周转口径 ----------

    @Test
    void turnoverStandardSnapshotDenominator() {
        Map<String, Object> t = reportService.turnover("2020-03-01", "2020-03-31",
                InvReportService.SCOPE_SALES);
        assertEquals("SNAPSHOT", t.get("denominatorMode"), "快照齐 → 标准口径");
        assertFalse(Boolean.TRUE.equals(t.get("degraded")));
        // 分子 = 60×10 = 600；分母 = (10+30)×10 / 2 = 200 → rate 3.0
        assertEquals(600d, ((Number) t.get("numerator")).doubleValue(), 0.01);
        assertEquals(200d, ((Number) t.get("denominator")).doubleValue(), 0.01);
        assertEquals(3.0, ((Number) t.get("rate")).doubleValue(), 0.001);
        // 天数 = 31 / 3.0
        assertEquals(31 / 3.0, ((Number) t.get("turnoverDays")).doubleValue(), 0.01);
        assertNotNull(t.get("staleRatio"), "呆滞占比指标卡有值");
    }

    @Test
    void turnoverAllOutScope() {
        Map<String, Object> t = reportService.turnover("2020-03-01", "2020-03-31",
                InvReportService.SCOPE_ALL_OUT);
        // 全部出库 = 600 + 400 = 1000；分母 200 → rate 5.0
        assertEquals(1000d, ((Number) t.get("numerator")).doubleValue(), 0.01);
        assertEquals(5.0, ((Number) t.get("rate")).doubleValue(), 0.001);
        assertTrue(String.valueOf(t.get("scopeLabel")).contains("非指标字典"),
                String.valueOf(t.get("scopeLabel")));
    }

    @Test
    void turnoverDegradedWithoutSnapshots() {
        // 2021-01：无任何快照 → 降级当前库存金额（全局 >0），标注 degraded
        Map<String, Object> t = reportService.turnover("2021-01-01", "2021-01-31",
                InvReportService.SCOPE_SALES);
        assertEquals("DEGRADED", t.get("denominatorMode"));
        assertTrue(Boolean.TRUE.equals(t.get("degraded")), "降级标记");
        assertNotNull(t.get("degradedReason"));
        assertEquals(0L, ((Number) t.get("snapshotDays")).longValue());
        // 分母 = 当前库存金额（A 80×10 + N 20×5 = 900，且全局其他库存 ≥ 它）
        assertTrue(((Number) t.get("denominator")).doubleValue() >= 900d);
    }

    @Test
    void turnoverZeroDenominatorNotApplicable() {
        // 2020-04：快照存在但金额 0 → 分母 0 → rate/days = null（不适用，spec 除零保护）
        Map<String, Object> t = reportService.turnover("2020-04-01", "2020-04-30",
                InvReportService.SCOPE_SALES);
        assertEquals("SNAPSHOT", t.get("denominatorMode"));
        assertEquals(0d, ((Number) t.get("denominator")).doubleValue());
        assertNull(t.get("rate"), "分母 0 → 不适用");
        assertNull(t.get("turnoverDays"));
    }

    @Test
    void summaryGroupsByAbcWithUnclassified() {
        List<Map<String, Object>> rows = reportService.turnoverSummary("2020-03-01",
                "2020-03-31", InvReportService.SCOPE_SALES, "ABC");
        List<String> keys = rows.stream().map(r -> String.valueOf(r.get("groupKey"))).toList();
        assertTrue(keys.contains("A"), "A 组存在：" + keys);
        assertTrue(keys.contains("未分类"), "空 ABC 归未分类：" + keys);
        Map<String, Object> aRow = rows.stream()
                .filter(r -> "A".equals(r.get("groupKey"))).findFirst().orElseThrow();
        assertEquals(600d, ((Number) aRow.get("outCost")).doubleValue(), 0.01);
        assertEquals("SNAPSHOT", aRow.get("denominatorMode"));
    }

    // ---------- 库龄与呆滞 ----------

    @Test
    void agingBucketsWithStaleFlag() {
        Map<String, Object> b = reportService.agingBuckets(WH, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) b.get("buckets");
        // A 80（400 天 → >180 桶）+ N 20（10 天 → 0-30 桶）
        Map<String, Object> over180 = buckets.stream()
                .filter(x -> ">180".equals(x.get("bucket"))).findFirst().orElseThrow();
        assertEquals(1L, ((Number) over180.get("count")).longValue());
        assertTrue(Boolean.TRUE.equals(over180.get("stale")));
        Map<String, Object> d30 = buckets.stream()
                .filter(x -> "0-30".equals(x.get("bucket"))).findFirst().orElseThrow();
        assertEquals(1L, ((Number) d30.get("count")).longValue());
        // 呆滞金额 = 80×10 = 800；总金额 900 → 占比 88.9%
        assertEquals(800d, ((Number) b.get("staleAmount")).doubleValue(), 0.01);
        assertEquals(800d / 900d * 100, ((Number) b.get("staleRatio")).doubleValue(), 0.01);
        assertEquals(180, ((Number) b.get("staleThreshold")).intValue());
    }

    @Test
    void agingListStaleOnlyAndAbcFilter() {
        Map<String, Object> stale = reportService.agingList(WH, null, null, true, 1, 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) stale.get("records");
        assertEquals(1, rows.size(), "仅呆滞行（400 天）");
        assertEquals(ITEM_A, rows.get(0).get("ITEM_CODE"));

        // ABC 过滤：未分类（NONE）只出 N 行
        Map<String, Object> none = reportService.agingList(WH, null, "NONE", false, 1, 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> noneRows = (List<Map<String, Object>>) none.get("records");
        assertEquals(1, noneRows.size());
        assertEquals(ITEM_N, noneRows.get(0).get("ITEM_CODE"));
    }

    @Test
    void exportRowsAgingAgeColumn() {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = reportService.exportRows("aging",
                Map.of("warehouseCode", WH, "maxRows", 50));
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) data.get("rows");
        assertEquals(2, rows.size());
        boolean hasStale = rows.stream().anyMatch(r -> "是".equals(r.get(10)));
        assertTrue(hasStale, "呆滞列标记");
    }
}
