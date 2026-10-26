package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.service.fin.CostReportService;
import com.erp.service.mrp.CostAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期间成本报表 DbTest（M8-10-3，change add-cost-accounting task 5.1）。
 * 造数：固定 ID 命名空间 CTR-（period 209901）；@BeforeEach 清场，互不残留。
 * 覆盖：汇总=明细合计、超容差红标 TOLERANCE 同口径、无产量分母剔除、
 * 空期间全零、CSV 同源、trend 单期、期间 422、审阅双角色（FINANCE_MGR 放行/第三方 403）。
 */
@SpringBootTest
class CostReportDbTest {

    private static final String PERIOD = "209901";
    /** 造数行：CTR1 高差异 1.2%（超 0.5% 容差）有产量；CTR2 低差异 0.4% 无产量 */
    private static final String MO1 = "mo-ctr-1";
    private static final String MO2 = "mo-ctr-2";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CostReportService reportService;
    @Autowired
    private CostAggregationService aggregationService;

    @BeforeEach
    void seed() {
        purge();
        jdbc.update("INSERT INTO erp_mrp_mo (ID, MO_NO, MO_TYPE, PRODUCT_CODE, PRODUCT_NAME, QTY, "
                + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('mo-ctr-1', 'MOCTR0001', 'NORMAL', 'CTRA001', '报表产成品A', 100, 'CLOSED', "
                + "'junit', '0', 0), "
                + "('mo-ctr-2', 'MOCTR0002', 'NORMAL', 'CTRB001', '报表产成品B', 50, 'CLOSED', "
                + "'junit', '0', 0)");
        // CTR1：料100+工50+费25=175，标准160，差异+15（率 0.012 > 0.005），产量100，RAISED
        insert(MO1, "MOCTR0001", 100, 50, 25, 175, "160", "15", "0.012", "100", "RAISED");
        // CTR2：料200+工100+费50=350，标准351.4064，差异-1.4064（率 0.004 ≤ 0.005），产量0，未生成
        insert(MO2, "MOCTR0002", 200, 100, 50, 350, "351.41", "-1.41", "0.004", "0", "NONE");
    }

    @AfterEach
    void cleanup() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_mrp_cost_summary WHERE MO_ID LIKE 'mo-ctr-%'");
        jdbc.update("DELETE FROM erp_mrp_mo WHERE ID LIKE 'mo-ctr-%'");
    }

    private void insert(String moId, String moNo, int mat, int lab, int oh, int total,
                        String std, String var, String rate, String goodQty, String reportStatus) {
        jdbc.update("INSERT INTO erp_mrp_cost_summary (ID, MO_ID, MO_NO, PERIOD, MATERIAL_COST, "
                + "LABOR_COST, OVERHEAD_COST, TOTAL_COST, GOOD_QTY, UNIT_COST, STD_TOTAL_COST, "
                + "VAR_TOTAL, VAR_RATE, REPORT_STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'junit', '0', 0)",
                "ctr-" + moId, moId, moNo, PERIOD, mat, lab, oh, total, goodQty,
                goodQty.equals("0") ? "0" : String.valueOf(total * 100L / Long.parseLong(goodQty)),
                std, var, rate, reportStatus);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(Map<String, Object> report) {
        return (Map<String, Object>) report.get("summary");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("rows");
    }

    // ---------- 3.1 报表聚合口径 ----------

    @Test
    void summaryEqualsDetailTotals() {
        Map<String, Object> rep = reportService.report(PERIOD, null, null);
        Map<String, Object> s = summaryOf(rep);
        List<Map<String, Object>> rows = rowsOf(rep);
        assertEquals(2, s.get("moCount"), "工单数=明细行数");
        assertEquals(2, rows.size());
        // 汇总 = 明细列合计（spec：同源聚合）
        java.math.BigDecimal mat = rows.stream().map(r -> (java.math.BigDecimal) r.get("materialCost"))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        assertEquals(0, mat.compareTo((java.math.BigDecimal) s.get("materialCost")), "材料合计");
        assertEquals(0, new java.math.BigDecimal("300").compareTo((java.math.BigDecimal) s.get("materialCost")));
        assertEquals(0, new java.math.BigDecimal("525").compareTo((java.math.BigDecimal) s.get("totalCost")));
    }

    @Test
    void overToleranceRedFlagByRate() {
        Map<String, Object> rep = reportService.report(PERIOD, null, null);
        Map<String, Object> s = summaryOf(rep);
        List<Map<String, Object>> rows = rowsOf(rep);
        Map<String, Object> high = rows.stream().filter(r -> MO1.equals(r.get("moId"))).findFirst().orElseThrow();
        Map<String, Object> low = rows.stream().filter(r -> MO2.equals(r.get("moId"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, high.get("overTolerance"), "1.2% > 0.5% 红标");
        assertEquals(Boolean.FALSE, low.get("overTolerance"), "0.4% ≤ 0.5% 不标");
        assertEquals(1, s.get("overToleranceCount"), "超容差计数=1");
    }

    @Test
    void noQtyRowsExcludedFromWeightedUnitCost() {
        Map<String, Object> rep = reportService.report(PERIOD, null, null);
        Map<String, Object> s = summaryOf(rep);
        assertEquals(1, s.get("noQtyCount"), "CTR2 产量 0 计入无产量");
        // 分母仅 CTR1 的 100：525 / 100 = 5.25
        assertEquals(0, new java.math.BigDecimal("5.25")
                .compareTo((java.math.BigDecimal) s.get("weightedUnitCost")), "加权单位成本剔除零产分行");
    }

    @Test
    void emptyPeriodReturnsZeroNoError() {
        Map<String, Object> rep = reportService.report("209902", null, null);
        Map<String, Object> s = summaryOf(rep);
        assertEquals(0, s.get("moCount"));
        assertTrue(rowsOf(rep).isEmpty(), "空期间明细空");
    }

    @Test
    void badPeriod422() {
        assertEquals(422, assertThrows(ServiceException.class,
                () -> reportService.report("2099", null, null)).getCode(), "期间格式 422");
    }

    // ---------- 3.2 CSV + trend ----------

    @Test
    void csvMatchesFilteredRows() {
        String csv = reportService.csv(PERIOD, null, null);
        String[] lines = csv.strip().split("\n");
        assertEquals(3, lines.length, "BOM 表头 + 2 数据行");
        assertTrue(lines[0].contains("直接材料") && lines[0].contains("超容差"), "中文表头");
        // 物料过滤：只留 CTRA001 一行
        String csvA = reportService.csv(PERIOD, "CTRA001", null);
        assertEquals(2, csvA.strip().split("\n").length, "筛选后表头+1行");
        assertTrue(csvA.contains("MOCTR0001"));
    }

    @Test
    void trendGroupsByPeriod() {
        List<Map<String, Object>> trend = reportService.trend();
        Map<String, Object> p = trend.stream().filter(t -> PERIOD.equals(t.get("period")))
                .findFirst().orElseThrow();
        assertEquals(2, p.get("moCount"), "trend 期间工单数");
        assertEquals(0, new java.math.BigDecimal("525")
                .compareTo((java.math.BigDecimal) p.get("totalCost")), "trend 总成本聚合");
    }

    // ---------- 5.1 审阅双角色（向角色表 2912 归位，design D2） ----------

    @Test
    void financeMgrCanReviewPlanMgrUnchanged() {
        // 财务主管审阅 RAISED → REVIEWED（spec 明文放行）
        BomTestAuth.login("finmgr", "ROLE_FINANCE_MGR");
        aggregationService.reviewVariance(MO1);
        assertEquals("REVIEWED", statusOf(MO1), "财务主管审阅放行");
        BomTestAuth.logout();
        // 计划主管不回归：再造一条 RAISED 给 PLAN_MGR 审
        jdbc.update("UPDATE erp_mrp_cost_summary SET REPORT_STATUS = 'RAISED' WHERE MO_ID = ?", MO2);
        BomTestAuth.login("mgr", "ROLE_PLAN_MGR");
        aggregationService.reviewVariance(MO2);
        assertEquals("REVIEWED", statusOf(MO2), "计划主管审阅不回归");
        BomTestAuth.logout();
        // 第三方角色 403（COST_ACCOUNTANT 无审阅权——CostAggregationDbTest 既有口径）
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> aggregationService.reviewVariance(MO1)).getCode(), "第三方 403");
        BomTestAuth.logout();
    }

    private String statusOf(String moId) {
        return jdbc.queryForObject("SELECT REPORT_STATUS FROM erp_mrp_cost_summary WHERE MO_ID = ?",
                String.class, moId);
    }
}
