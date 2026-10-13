package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.service.SysParamService;
import com.erp.service.mrp.CostAggregationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成本归集 DbTest（change add-cost-aggregation，task 2.1/2.2/2.3）。
 * 造数：固定 ID 命名空间 CST-；@BeforeEach 清场重建，互不残留。
 */
@SpringBootTest
class CostAggregationDbTest {

    private static final String MO1 = "mo-cst-1";      // 正常归集（料双价源 + 工时）
    private static final String MO2 = "mo-cst-2";      // 单价双缺 → 422
    private static final String MO_NO1 = "MOCST0001";
    private static final String MO_NO2 = "MOCST0002";
    private static final String ITEM_A = "CSTA001";    // 标准成本 5
    private static final String ITEM_B = "CSTB001";    // 无标准成本 → PO 价 3
    private static final String ITEM_C = "CSTC001";    // 双缺
    private static final String PROD = "CSTP001";      // 产成品，标准成本 100

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CostAggregationService service;
    @Autowired
    private SysParamService sysParam;
    @Autowired
    private com.erp.service.mrp.MoService moService;

    @BeforeEach
    void seed() {
        purge();
        // 物料：A 有标准成本；B/C 无；产成品标准成本 100
        for (String[] it : new String[][]{
                {ITEM_A, "归集料A"}, {ITEM_B, "归集料B"}, {ITEM_C, "归集料C"}, {PROD, "归集产成品"}}) {
            jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                    + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                    + "STATUS, CREATE_BY, DEL_FLAG) VALUES (?, ?, ?, '0001', 'KG', "
                    + "'G001', 'BUY', 'NORMAL', '1', '0', '1', 'junit', '0')",
                    "it-" + it[0].toLowerCase(), it[0], it[1]);
        }
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 5 WHERE ITEM_CODE = ?", ITEM_A);
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 100 WHERE ITEM_CODE = ?", PROD);
        // B 的采购价兜底：APPROVED PO 行单价 3
        jdbc.update("INSERT INTO erp_proc_po (ID, PO_NO, SOURCE, SUPPLIER_ID, STATUS, CREATE_BY, "
                + "DEL_FLAG, VER_NO) VALUES ('po-cst-b', 'POCST01', 'NORMAL', 'sup-x', 'APPROVED', "
                + "'junit', '0', 0)");
        jdbc.update("INSERT INTO erp_proc_po_line (ID, PO_ID, LINE_NO, ITEM_CODE, ITEM_NAME, QTY, "
                + "UNIT, UNIT_PRICE, CREATE_BY) VALUES ('pol-cst-b', 'po-cst-b', 1, ?, '归集料B', "
                + "100, 'KG', 3, 'junit')", ITEM_B);
        // 工单
        jdbc.update("INSERT INTO erp_mrp_mo (ID, MO_NO, MO_TYPE, PRODUCT_CODE, PRODUCT_NAME, QTY, "
                + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'NORMAL', ?, '归集产成品', "
                + "50, 'RELEASED', 'junit', '0', 0)", MO1, MO_NO1, PROD);
        jdbc.update("UPDATE erp_mrp_mo SET QUALIFIED_QTY = 20 WHERE ID = ?", MO1);
        jdbc.update("INSERT INTO erp_mrp_mo (ID, MO_NO, MO_TYPE, PRODUCT_CODE, PRODUCT_NAME, QTY, "
                + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'NORMAL', ?, '归集产成品', "
                + "10, 'RELEASED', 'junit', '0', 0)", MO2, MO_NO2, PROD);
        // 试产工单（完工钩子须跳过 TRIAL——试产走自有归集）
        jdbc.update("INSERT INTO erp_mrp_mo (ID, MO_NO, MO_TYPE, PRODUCT_CODE, PRODUCT_NAME, QTY, "
                + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('mo-cst-t', 'MOCSTT01', 'TRIAL', "
                + "?, '归集产成品', 5, 'RELEASED', 'junit', '0', 0)", PROD);
        // 投料：A 100（标准价 5）、B 50（PO 价 3）、退料行 999 须排除
        insertFeed("fd-a", MO1, ITEM_A, "归集料A", "100", "0");
        insertFeed("fd-b", MO1, ITEM_B, "归集料B", "50", "0");
        insertFeed("fd-r", MO1, ITEM_A, "归集料A", "999", "1");
        insertFeed("fd-c", MO2, ITEM_C, "归集料C", "10", "0");
        // 报工：CONFIRMED 1h+5h；零工时行跳过；OPEN 行排除
        insertRpt("rpt-c1", MO1, "1", "5", "2", "CONFIRMED");
        insertRpt("rpt-c2", MO1, "0", "0", "0", "CONFIRMED");
        insertRpt("rpt-c3", MO1, "9", "9", "9", "OPEN");
    }

    @AfterEach
    void cleanup() {
        purge();
        sysParam.setValue("STD_LABOR_RATE", "50", "NUMBER", "MRP", "标准工资率", "junit");
    }

    private void purge() {
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v "
                + "ON v.ID = l.VOUCHER_ID WHERE v.SOURCE_TYPE = 'MO_COMPLETE' "
                + "AND v.SOURCE_DOC_NO IN ('MOCST0001', 'MOCST0002')");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_COMPLETE' "
                + "AND SOURCE_DOC_NO IN ('MOCST0001', 'MOCST0002')");
        jdbc.update("DELETE FROM erp_mrp_cost_line WHERE MO_ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_cost_summary WHERE MO_ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_overhead_pool WHERE PERIOD = ?", periodNow());
        jdbc.update("DELETE FROM erp_mrp_feed WHERE MO_ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_reporting WHERE MO_ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_mo WHERE ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_mo WHERE ID = 'mo-cst-t'");
        jdbc.update("DELETE FROM erp_proc_po_line WHERE ID = 'pol-cst-b'");
        jdbc.update("DELETE FROM erp_proc_po WHERE ID = 'po-cst-b'");
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ID = 'rto-cst'");
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE ID = 'owc-cst'");
        jdbc.update("UPDATE erp_mrp_mo SET SOURCE_ROUTING_ID = NULL WHERE ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE IN (?, ?, ?, ?)",
                ITEM_A, ITEM_B, ITEM_C, PROD);
    }

    private void insertFeed(String id, String moId, String item, String name, String qty, String ret) {
        jdbc.update("INSERT INTO erp_mrp_feed (ID, MO_ID, ITEM_CODE, ITEM_NAME, QTY, RETURNED_FLAG, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, ?, ?, 'junit', '0', 0)",
                id, moId, item, name, qty, ret);
    }

    private void insertRpt(String id, String moId, String setup, String run, String mach, String st) {
        jdbc.update("INSERT INTO erp_mrp_reporting (ID, RPT_NO, DISPATCH_ID, MO_ID, OP_SEQ, "
                + "SETUP_HOURS, RUN_HOURS, MACHINE_HOURS, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'dp-cst-x', ?, 10, ?, ?, ?, ?, 'junit', '0', 0)",
                id, id.toUpperCase(), moId, setup, run, mach, st);
    }

    // ---------- task 2.1：材料/人工 ----------

    @Test
    void materialAndLaborAggregation() {
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        Map<String, Object> out = service.aggregate(MO1);

        // 材料 = 100×5 + 50×3 = 650（标准价 + PO 兜底价；退料行排除）
        assertEquals(0, new BigDecimal("650").compareTo((BigDecimal) out.get("material")),
                "材料 = 标准价与 PO 兜底价合计 650");
        // 人工 = (1+5)×50 = 300（零工时行跳过、OPEN 行排除）
        assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) out.get("labor")),
                "人工 = 6h × 50 = 300");

        Map<String, Object> d = service.detail(MO1);
        assertNotNull(d.get("summary"), "summary 已落库");
        @SuppressWarnings("unchecked")
        List<com.erp.entity.mrp.CostLine> lines =
                (List<com.erp.entity.mrp.CostLine>) d.get("lines");
        long materialLines = lines.stream()
                .filter(l -> com.erp.entity.mrp.CostLine.T_MATERIAL.equals(l.getCostType())).count();
        long laborLines = lines.stream()
                .filter(l -> com.erp.entity.mrp.CostLine.T_LABOR.equals(l.getCostType())).count();
        assertEquals(2, materialLines, "材料行 = A/B 两物料");
        assertEquals(1, laborLines, "人工行 = 仅 1 条有效报工");
    }

    @Test
    void missingPriceBlocksAggregation() {
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.aggregate(MO2));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains(ITEM_C), "阻断消息须列出缺价物料");
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_cost_summary WHERE MO_ID = ?", Long.class, MO2);
        assertEquals(0L, cnt, "阻断后不落 summary");
    }

    @Test
    void zeroHoursAndOpenReportingExcluded() {
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        service.aggregate(MO1);
        Long laborRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_cost_line WHERE MO_ID = ? AND COST_TYPE = 'LABOR'",
                Long.class, MO1);
        assertEquals(1L, laborRows, "零工时与 OPEN 报工均不产生人工行");
    }

    @Test
    void invalidRateFallsBackToDefault() {
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        sysParam.setValue("STD_LABOR_RATE", "ABC", "NUMBER", "MRP", "非法值测试", "junit");
        Map<String, Object> out = service.aggregate(MO1);
        assertEquals(0, new BigDecimal("300").compareTo((BigDecimal) out.get("labor")),
                "参数非法 → 默认费率 50");
    }

    @Test
    void readerForbiddenToAggregate() {
        BomTestAuth.login("plan", "ROLE_PLANNER");
        // 手动重跑是写操作（spec「计划线只读」：按钮禁用 + 写接口 403）
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.rerun(MO1)).getCode(), "计划线重跑 403");
        BomTestAuth.login("qc", "ROLE_INSPECTOR");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.rerun(MO1)).getCode(), "质检重跑 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.list(null, null)).getCode(), "质检查列表 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.detail(MO1)).getCode(), "质检查详情 403");
        // 计划线读路径可用（只读可见）
        BomTestAuth.login("plan", "ROLE_PLANNER");
        service.detail(MO1); // 不抛即通过（未归集返回 summary=null 亦可）
    }

    // ---------- task 2.4：触发与重跑 ----------

    @Test
    void completionTriggersAutoAggregationAndSkipsTrial() {
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("20"));
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_cost_summary WHERE MO_ID = ? AND DEL_FLAG = '0'",
                Long.class, MO1);
        assertEquals(1L, cnt, "完工自动归集生成 summary（计划员触发，系统路径无角色门槛）");
        // TRIAL 跳过
        moService.complete("mo-cst-t", new BigDecimal("5"));
        Long trialCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_cost_summary WHERE MO_ID = 'mo-cst-t' AND DEL_FLAG = '0'",
                Long.class);
        assertEquals(0L, trialCnt, "试产工单完工不走量产归集");
    }

    @Test
    void rerunRebuildsIdempotently() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        Map<String, Object> first = service.rerun(MO1);
        Map<String, Object> second = service.rerun(MO1);
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_cost_summary WHERE MO_ID = ? AND DEL_FLAG = '0'",
                Long.class, MO1);
        assertEquals(1L, cnt, "重建式重跑始终单行");
        assertEquals(0, ((BigDecimal) first.get("material"))
                .compareTo((BigDecimal) second.get("material")), "重跑金额稳定幂等");
        Long lines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_cost_line WHERE MO_ID = ? AND DEL_FLAG = '0'",
                Long.class, MO1);
        Long expectedLines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT ITEM_CODE FROM erp_mrp_feed WHERE MO_ID = ? "
                        + "AND RETURNED_FLAG = '0' GROUP BY ITEM_CODE) t", Long.class, MO1);
        // 明细行 = 物料分组数 + 有效报工数 + 1（OH 行）
        assertEquals(expectedLines + 1 + 1, lines, "重跑不叠加明细行");
    }

    // ---------- task 2.2：费用池与分摊 ----------

    private String periodNow() {
        return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private void purgePool() {
        jdbc.update("DELETE FROM erp_mrp_overhead_pool WHERE PERIOD = ?", periodNow());
    }

    /** 与实现同口径的总基准（TIME 规则） */
    private BigDecimal totalBaselineSql() {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(r.SETUP_HOURS + r.RUN_HOURS), 0) "
                        + "FROM erp_mrp_reporting r JOIN erp_mrp_mo m ON m.ID = r.MO_ID "
                        + "WHERE r.STATUS = 'CONFIRMED' AND r.DEL_FLAG = '0' "
                        + "AND m.DEL_FLAG = '0' AND m.MO_TYPE <> 'TRIAL'",
                BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    @Test
    void poolAllocationTimeRule() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        service.savePool(Map.of("period", periodNow(), "poolAmt", "10000", "rule", "TIME"));
        Map<String, Object> out = service.aggregate(MO1);

        BigDecimal den = totalBaselineSql();
        BigDecimal expected = new BigDecimal("10000").divide(den, 6,
                        java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("6")).setScale(4, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo((BigDecimal) out.get("overhead")),
                "工时比例分摊 = 池 × 6/总基准");
    }

    @Test
    void poolRuleSwitchQtyBaseline() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        service.savePool(Map.of("period", periodNow(), "poolAmt", "5000", "rule", "QTY"));
        Map<String, Object> out = service.aggregate(MO1);

        BigDecimal den = jdbc.queryForObject(
                "SELECT COALESCE(SUM(QUALIFIED_QTY), 0) FROM erp_mrp_mo "
                        + "WHERE DEL_FLAG = '0' AND MO_TYPE <> 'TRIAL'", BigDecimal.class);
        BigDecimal expected = new BigDecimal("5000").divide(den, 6,
                        java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("20")).setScale(4, java.math.RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo((BigDecimal) out.get("overhead")),
                "产量比例分摊 = 池 × 合格产出/总合格产出");
    }

    @Test
    void poolPeriodUniqueUpsert() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        Map<String, Object> first = service.savePool(
                Map.of("period", periodNow(), "poolAmt", "5000"));
        assertEquals(Boolean.TRUE, first.get("created"), "首次录入建池");
        Map<String, Object> second = service.savePool(
                Map.of("period", periodNow(), "poolAmt", "7000"));
        assertEquals(Boolean.FALSE, second.get("created"), "同期再次保存为更新（期次唯一）");
        List<com.erp.entity.mrp.OverheadPool> pools = service.listPools().stream()
                .filter(p -> periodNow().equals(p.getPeriod())).toList();
        assertEquals(1, pools.size(), "同池仅一行");
        assertEquals(0, new BigDecimal("7000").compareTo(pools.get(0).getPoolAmt()));
        assertEquals("TIME", pools.get(0).getRule(), "未配置规则默认 TIME");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.savePool(
                Map.of("period", "2026X1", "poolAmt", "1")));
        assertEquals(422, ex.getCode(), "期次格式非法 422");
    }

    @Test
    void poolMissingMarksZeroOverhead() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        Map<String, Object> out = service.aggregate(MO1);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("overhead")),
                "缺池制费记 0");
        Map<String, Object> d = service.detail(MO1);
        @SuppressWarnings("unchecked")
        com.erp.entity.mrp.CostSummary s =
                (com.erp.entity.mrp.CostSummary) d.get("summary");
        assertNotNull(s.getMark(), "缺池标记落 summary");
        assertTrue(s.getMark().contains("费用池未维护"), "标记文案含「费用池未维护」");
        @SuppressWarnings("unchecked")
        List<com.erp.entity.mrp.CostLine> lines =
                (List<com.erp.entity.mrp.CostLine>) d.get("lines");
        assertTrue(lines.stream().anyMatch(l ->
                        com.erp.entity.mrp.CostLine.T_OH.equals(l.getCostType())
                                && com.erp.entity.mrp.CostLine.MARK_NO_POOL.equals(l.getMark())),
                "OH 行带 NO_POOL 标记");
    }

    // ---------- task 2.3：汇总与四分解差异 ----------

    private com.erp.entity.mrp.CostSummary summaryOf(String moId) {
        return jdbc.queryForObject(
                "SELECT * FROM erp_mrp_cost_summary WHERE MO_ID = ? AND DEL_FLAG = '0'",
                (rs, i) -> {
                    com.erp.entity.mrp.CostSummary s = new com.erp.entity.mrp.CostSummary();
                    s.setMoId(rs.getString("MO_ID"));
                    s.setMaterialCost(rs.getBigDecimal("MATERIAL_COST"));
                    s.setLaborCost(rs.getBigDecimal("LABOR_COST"));
                    s.setOverheadCost(rs.getBigDecimal("OVERHEAD_COST"));
                    s.setTotalCost(rs.getBigDecimal("TOTAL_COST"));
                    s.setGoodQty(rs.getBigDecimal("GOOD_QTY"));
                    s.setUnitCost(rs.getBigDecimal("UNIT_COST"));
                    s.setStdTotalCost(rs.getBigDecimal("STD_TOTAL_COST"));
                    s.setVarTotal(rs.getBigDecimal("VAR_TOTAL"));
                    s.setVarPrice(rs.getBigDecimal("VAR_PRICE"));
                    s.setVarVolume(rs.getBigDecimal("VAR_VOLUME"));
                    s.setVarEfficiency(rs.getBigDecimal("VAR_EFFICIENCY"));
                    s.setVarCapacity(rs.getBigDecimal("VAR_CAPACITY"));
                    s.setVarRate(rs.getBigDecimal("VAR_RATE"));
                    s.setReportStatus(rs.getString("REPORT_STATUS"));
                    s.setMark(rs.getString("MARK"));
                    return s;
                }, moId);
    }

    @Test
    void summaryIdentityAndUnitCost() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        service.aggregate(MO1);
        com.erp.entity.mrp.CostSummary s = summaryOf(MO1);
        assertEquals(0, s.getTotalCost().compareTo(s.getMaterialCost()
                .add(s.getLaborCost()).add(s.getOverheadCost())), "总成本 = 料+工+费");
        assertEquals(0, s.getGoodQty().compareTo(new BigDecimal("20")), "合格产出取工单");
        BigDecimal expectedUnit = s.getTotalCost().divide(new BigDecimal("20"), 4,
                java.math.RoundingMode.HALF_UP);
        assertEquals(0, expectedUnit.compareTo(s.getUnitCost()), "单位成本 = 总成本/合格产出");
    }

    @Test
    void fourWayVarianceDecomposition() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        // 路线与工序定额：标准工时 = (0.5+1.5) × 合格 20 = 40h；实际 6h
        jdbc.update("INSERT INTO erp_mrp_routing_op (ID, ROUTING_ID, OP_SEQ, OP_CODE, OP_NAME, "
                + "WC_CODE, WC_NAME, LEAD_TIME, STANDARD_YIELD, KEY_OP_FLAG, CREATE_BY, DEL_FLAG) "
                + "VALUES ('rto-cst', 'rt-cst', 10, 'CSTOP10', '归集工序', 'CSTWC', '归集中心', "
                + "0, 90, 0, 'junit', '0')");
        jdbc.update("INSERT INTO erp_mrp_op_wc_standard (ID, OP_CODE, WC_CODE, SETUP_HOURS, "
                + "RUN_HOURS, CREATE_BY, DEL_FLAG) VALUES ('owc-cst', 'CSTOP10', 'CSTWC', "
                + "0.5, 1.5, 'junit', '0')");
        jdbc.update("UPDATE erp_mrp_mo SET SOURCE_ROUTING_ID = 'rt-cst' WHERE ID = ?", MO1);
        service.aggregate(MO1);
        com.erp.entity.mrp.CostSummary s = summaryOf(MO1);

        // 价格差异恒 0（生产侧按标准价领用，采购 1406 已确认）
        assertEquals(0, BigDecimal.ZERO.compareTo(s.getVarPrice()), "价格差异记 0");
        // 用量差异 = (100−0)×5 + (50−0)×3 = 650（无 BOM 快照 → 标准用量 0）
        assertEquals(0, new BigDecimal("650").compareTo(s.getVarVolume()), "用量差异实算 650");
        // 效率差异 = (6 − 40) × 50 = -1700
        assertEquals(0, new BigDecimal("-1700").compareTo(s.getVarEfficiency()),
                "效率差异实算 -1700");
        // 产能差异 = OH(0) × (1−负荷) = 0（缺池无派工）
        assertEquals(0, BigDecimal.ZERO.compareTo(s.getVarCapacity()), "产能差异随 OH 为 0");
        // 总差异 = 实际 950 − 标准 100×20 = −1050；差异率 52.5% > 5% → RAISED
        assertEquals(0, new BigDecimal("-1050").compareTo(s.getVarTotal()), "总差异 = 实际−标准");
        assertEquals(0, new BigDecimal("0.5250").compareTo(s.getVarRate()), "差异率 52.5%");
        assertEquals("RAISED", s.getReportStatus(), "超阈值生成报告");
        // 清理本用例的路线数据
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ID = 'rto-cst'");
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE ID = 'owc-cst'");
        jdbc.update("UPDATE erp_mrp_mo SET SOURCE_ROUTING_ID = NULL WHERE ID = ?", MO1);
    }

    @Test
    void underThresholdNoReport() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        // 标准成本调至与实际一致（950/20 = 47.5）→ 差异率 0 → 不生成报告
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 47.5 WHERE ITEM_CODE = ?", PROD);
        service.aggregate(MO1);
        com.erp.entity.mrp.CostSummary s = summaryOf(MO1);
        assertEquals(0, BigDecimal.ZERO.compareTo(s.getVarRate()), "实际=标准 → 差异率 0");
        assertEquals("NONE", s.getReportStatus(), "未超阈值不生成报告");
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 100 WHERE ITEM_CODE = ?", PROD);
    }

    @Test
    void reviewVarianceFlowAndSurviveRebuild() {
        purgePool();
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        service.aggregate(MO1);
        assertEquals("RAISED", summaryOf(MO1).getReportStatus());
        // 成本会计无审阅权
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.reviewVariance(MO1)).getCode(), "审阅须主管");
        // 计划主管审阅 → REVIEWED
        BomTestAuth.login("mgr", "ROLE_PLAN_MGR");
        service.reviewVariance(MO1);
        assertEquals("REVIEWED", summaryOf(MO1).getReportStatus());
        // 重建后仍超阈值 → 保持 REVIEWED（重跑不丢审阅状态）
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        service.aggregate(MO1);
        assertEquals("REVIEWED", summaryOf(MO1).getReportStatus(), "重建保持已审阅");
        // 已审阅再审 422
        BomTestAuth.login("mgr", "ROLE_PLAN_MGR");
        assertEquals(422, assertThrows(ServiceException.class,
                () -> service.reviewVariance(MO1)).getCode());
    }

    // ---------- task 3.1：C-4.5-09 关单卡控 ----------

    @Test
    void checkForCloseGates() {
        purgePool();
        // ① 未归集 → L1 阻断，引导 5.11
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.checkForClose(MO1));
        assertEquals(422, e1.getCode());
        assertTrue(e1.getMessage().contains("5.11"), e1.getMessage());

        // ② 归集但缺池（NO_POOL 标记）→ 阻断
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        service.aggregate(MO1);
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.checkForClose(MO1));
        assertEquals(422, e2.getCode());
        assertTrue(e2.getMessage().contains("NO_POOL"), e2.getMessage());

        // ③ 池维护后重跑（池额 1，差异率仍超阈值）→ 未审阅差异阻断
        service.savePool(Map.of("period", periodNow(), "poolAmt", "1"));
        service.rerun(MO1);
        com.erp.entity.mrp.CostSummary s = summaryOf(MO1);
        assertEquals("RAISED", s.getReportStatus(), "实际 950 vs 标准 2000 → 超 5% 阈值");
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> service.checkForClose(MO1));
        assertEquals(422, e3.getCode());
        assertTrue(e3.getMessage().contains("未处理差异"), e3.getMessage());

        // ④ 主管审阅 → REVIEWED → 放行
        BomTestAuth.login("mgr", "ROLE_PLAN_MGR");
        service.reviewVariance(MO1);
        service.checkForClose(MO1); // 不抛即通过
    }
}
