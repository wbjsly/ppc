package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.fin.FinVoucher;
import com.erp.service.SysParamService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.mrp.CostAggregationService;
import com.erp.service.mrp.CostVoucherService;
import com.erp.service.mrp.MoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完工凭证 DbTest（change add-cost-aggregation，task 4.1/4.2）。
 * 造数与 CostAggregationDbTest 同口径（CST- 命名空间，独立类防相互清场）。
 */
@SpringBootTest
class CostVoucherDbTest {

    private static final String MO1 = "mo-vch-1";
    private static final String MO2 = "mo-vch-2";
    private static final String MO_NO1 = "MOVCH0001";
    private static final String MO_NO2 = "MOVCH0002";
    private static final String ITEM_A = "VCHA001";
    private static final String PROD = "VCHP001";   // 标准成本 100

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MoService moService;
    @Autowired
    private CostAggregationService costService;
    @Autowired
    private CostVoucherService voucherService;
    @Autowired
    private GlVoucherService glVoucherService;
    @Autowired
    private SysParamService sysParam;

    @BeforeEach
    void seed() {
        purge();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY, DEL_FLAG) VALUES ('it-vch-a', ?, '凭证料A', '0001', 'KG', "
                + "'G001', 'BUY', 'NORMAL', '1', '0', '1', 'junit', '0')", ITEM_A);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY, DEL_FLAG) VALUES ('it-vch-p', ?, '凭证产成品', '0001', 'KG', "
                + "'G001', 'MAKE', 'NORMAL', '0', '0', '1', 'junit', '0')", PROD);
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 5 WHERE ITEM_CODE = ?", ITEM_A);
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 100 WHERE ITEM_CODE = ?", PROD);
        for (String[] mo : new String[][]{{MO1, MO_NO1}, {MO2, MO_NO2}}) {
            jdbc.update("INSERT INTO erp_mrp_mo (ID, MO_NO, MO_TYPE, PRODUCT_CODE, PRODUCT_NAME, "
                    + "QTY, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'NORMAL', ?, "
                    + "'凭证产成品', 50, 'RELEASED', 'junit', '0', 0)", mo[0], mo[1], PROD);
            jdbc.update("UPDATE erp_mrp_mo SET QUALIFIED_QTY = 20 WHERE ID = ?", mo[0]);
            // 投料 A 100 × 5 = 500；报工 6h × 50 = 300 → 实际 800；标准 100×20 = 2000
            jdbc.update("INSERT INTO erp_mrp_feed (ID, MO_ID, ITEM_CODE, ITEM_NAME, QTY, "
                    + "RETURNED_FLAG, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, '凭证料A', "
                    + "100, '0', 'junit', '0', 0)", "fd-" + mo[0], mo[0], ITEM_A);
            jdbc.update("INSERT INTO erp_mrp_reporting (ID, RPT_NO, DISPATCH_ID, MO_ID, OP_SEQ, "
                    + "SETUP_HOURS, RUN_HOURS, MACHINE_HOURS, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                    + "VALUES (?, ?, 'dp-vch-x', ?, 10, 1, 5, 0, 'CONFIRMED', 'junit', '0', 0)",
                    "rpt-" + mo[0], "RPT" + mo[0].toUpperCase(), mo[0]);
        }
        // 无费用池（期间清理）→ 制费 0
        jdbc.update("DELETE FROM erp_mrp_overhead_pool WHERE PERIOD = ?", periodNow());
        sysParam.setValue("COST_CLOSED_PERIODS", "", "STRING", "MRP", "测试复位", "junit");
    }

    @AfterEach
    void cleanup() {
        purge();
        sysParam.setValue("COST_CLOSED_PERIODS", "", "STRING", "MRP", "测试复位", "junit");
        jdbc.update("UPDATE erp_fin_account SET STATUS = 'ACTIVE' "
                + "WHERE ACCOUNT_CODE IN ('500101', '500102', '500103', '500191', '1405')");
    }

    private void purge() {
        // 期间级清理：转销按期间汇总，冒烟/其他族同期间遗留凭证会污染净额断言
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v "
                + "ON v.ID = l.VOUCHER_ID WHERE v.SOURCE_TYPE = 'MO_COMPLETE' AND v.PERIOD = ?",
                periodNow());
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_COMPLETE' AND PERIOD = ?",
                periodNow());
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v "
                + "ON v.ID = l.VOUCHER_ID WHERE v.SOURCE_TYPE IN "
                + "('MO_COMPLETE', 'MO_VAR_TRANSFER') "
                + "AND (v.SOURCE_DOC_NO IN (?, ?) OR v.SOURCE_DOC_NO IN (?, '209912'))",
                MO_NO1, MO_NO2, periodNow());
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN "
                + "('MO_COMPLETE', 'MO_VAR_TRANSFER') "
                + "AND (SOURCE_DOC_NO IN (?, ?) OR SOURCE_DOC_NO IN (?, '209912'))",
                MO_NO1, MO_NO2, periodNow());
        jdbc.update("DELETE FROM erp_mrp_cost_line WHERE MO_ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_cost_summary WHERE MO_ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_feed WHERE MO_ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_reporting WHERE MO_ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mrp_mo WHERE ID IN (?, ?)", MO1, MO2);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE IN (?, ?)", ITEM_A, PROD);
    }

    private String periodNow() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    private long voucherCount(String moNo) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_COMPLETE' "
                        + "AND SOURCE_DOC_NO = ?", Long.class, moNo);
        return n == null ? 0 : n;
    }

    // ---------- task 4.1 ----------

    @Test
    void completionGeneratesBalancedVoucher() {
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("20"));

        assertEquals(1, voucherCount(MO_NO1), "完工生成一张凭证");
        FinVoucher v = jdbc.queryForObject(
                "SELECT * FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_COMPLETE' "
                        + "AND SOURCE_DOC_NO = ?",
                (rs, i) -> {
                    FinVoucher fv = new FinVoucher();
                    fv.setId(rs.getString("ID"));
                    fv.setVoucherNo(rs.getString("VOUCHER_NO"));
                    fv.setStatus(rs.getString("STATUS"));
                    fv.setPeriod(rs.getString("PERIOD"));
                    fv.setTotalDr(rs.getBigDecimal("TOTAL_DR"));
                    fv.setTotalCr(rs.getBigDecimal("TOTAL_CR"));
                    return fv;
                }, MO_NO1);
        assertEquals("POSTED", v.getStatus(), "生成即过账（BR-4.5-30）");
        assertNotNull(v.getVoucherNo());
        // 三段结构：借 1405 = 2000；贷 500101=500 / 500102=300；差额借 500191=1200
        assertEquals(0, new BigDecimal("2000").compareTo(v.getTotalDr()), "借方合计 2000");
        assertEquals(0, v.getTotalDr().compareTo(v.getTotalCr()), "借贷平衡");
        Map<String, BigDecimal> byAccount = jdbc.query(
                "SELECT ACCOUNT_CODE, DIRECTION, AMOUNT FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
                        + "WHERE v.SOURCE_DOC_NO = ?", rs -> {
                    Map<String, BigDecimal> m = new java.util.HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getString(1) + "/" + rs.getString(2), rs.getBigDecimal(3));
                    }
                    return m;
                }, MO_NO1);
        assertEquals(0, new BigDecimal("2000.00").compareTo(byAccount.get("1405/DR")),
                "借产成品 = 标准 100×20");
        assertEquals(0, new BigDecimal("500.00").compareTo(byAccount.get("500101/CR")),
                "贷生产成本-材料 = 100×5");
        assertEquals(0, new BigDecimal("300.00").compareTo(byAccount.get("500102/CR")),
                "贷生产成本-人工 = 6h×50");
        assertEquals(0, new BigDecimal("1200.00").compareTo(byAccount.get("500191/CR")),
                "差异行补平贷方：2000 − 800 = 1200（标准 > 实际 → 贷差异，借贷各 2000）");
    }

    @Test
    void rerunDoesNotCreateSecondVoucher() {
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("20"));
        assertEquals(1, voucherCount(MO_NO1));
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        costService.rerun(MO1);
        costService.rerun(MO1);
        assertEquals(1, voucherCount(MO_NO1), "重跑不触凭证（幂等锚工单号）");
    }

    @Test
    void missingAccountBlocksVoucherButNotCompletion() {
        jdbc.update("UPDATE erp_fin_account SET STATUS = 'DISABLED' WHERE ACCOUNT_CODE = '500101'");
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("20"));
        assertEquals(0, voucherCount(MO_NO1), "缺科目 L1 阻断，凭证不落库");
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_mrp_mo WHERE ID = ?", String.class, MO1);
        assertEquals("COMPLETED", st, "完工不被凭证失败回滚");
    }

    @Test
    void postedVoucherImmutable() {
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("20"));
        String vid = jdbc.queryForObject(
                "SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_COMPLETE' "
                        + "AND SOURCE_DOC_NO = ?", String.class, MO_NO1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> glVoucherService.update(vid, "改摘要"));
        assertEquals(422, ex.getCode(), "已过账不可改（BR-4.6-09）");
        assertEquals(422, assertThrows(ServiceException.class,
                () -> glVoucherService.voidDraft(vid)).getCode(), "POSTED 不可作废，仅红冲");
    }

    // ---------- task 4.2：期间卡控与查询面 ----------

    @Test
    void closedPeriodCreatesDraftAndKeepsAggregation() {
        sysParam.setValue("COST_CLOSED_PERIODS", periodNow(), "STRING", "MRP", "关期间测试", "junit");
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("20"));
        assertEquals(1, voucherCount(MO_NO1), "期间关闭仍生成凭证（DRAFT）");
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_COMPLETE' "
                        + "AND SOURCE_DOC_NO = ?", String.class, MO_NO1);
        assertEquals("DRAFT", st, "命中关闭期间 → DRAFT 待下期过账");
        // 归集不回滚
        Long agg = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_cost_summary WHERE MO_ID = ? AND DEL_FLAG = '0'",
                Long.class, MO1);
        assertEquals(1L, agg, "归集正常保留");
        // 非命中期间的另一单照常 POSTED（开放路径）
        sysParam.setValue("COST_CLOSED_PERIODS", "209901", "STRING", "MRP", "开放测试", "junit");
        moService.complete(MO2, new BigDecimal("20"));
        assertEquals("POSTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_COMPLETE' "
                        + "AND SOURCE_DOC_NO = ?", String.class, MO_NO2), "开放期间 → POSTED");
    }

    @Test
    void listFilterAndFinanceReadonlyPermission() {
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("20"));

        // 财务只读：列表/明细可查
        BomTestAuth.login("fin", "ROLE_FINANCE");
        List<Map<String, Object>> rows = voucherService.list("POSTED", periodNow());
        assertTrue(rows.stream().anyMatch(r -> MO_NO1.equals(r.get("sourceDocNo"))),
                "FINANCE 可查 POSTED 列表");
        assertEquals(0, voucherService.draftList().stream()
                .filter(r -> MO_NO1.equals(r.get("sourceDocNo"))).count(), "无 DRAFT 残留");
        String vid = (String) rows.get(0).get("id");
        assertNotNull(voucherService.detail(vid), "FINANCE 可查明细");
        // 财务写 403（过账动作）
        assertEquals(403, assertThrows(ServiceException.class,
                () -> voucherService.postDraft(vid)).getCode(), "FINANCE 过账 403");
        // 质检全面 403
        BomTestAuth.login("qc", "ROLE_INSPECTOR");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> voucherService.list(null, null)).getCode());
        // 成本会计：DRAFT 清单 + 过账
        sysParam.setValue("COST_CLOSED_PERIODS", periodNow(), "STRING", "MRP", "关期间", "junit");
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO2, new BigDecimal("20"));
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        List<Map<String, Object>> drafts = voucherService.draftList();
        Map<String, Object> draft = drafts.stream()
                .filter(r -> MO_NO2.equals(r.get("sourceDocNo"))).findFirst().orElse(null);
        assertNotNull(draft, "DRAFT 清单可见");
        voucherService.postDraft((String) draft.get("id"));
        assertEquals("POSTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_COMPLETE' "
                        + "AND SOURCE_DOC_NO = ?", String.class, MO_NO2), "过账动作生效");
        // 非完工来源凭证不可过账
        assertEquals(422, assertThrows(ServiceException.class,
                () -> voucherService.postDraft("nonexistent")).getCode());
    }

    @Test
    void zeroAmountSkipsVoucher() {
        // 标准与实际同为 0：产成品标准成本置 0、投料与报工删净 → 不生成凭证
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 0 WHERE ITEM_CODE = ?", PROD);
        jdbc.update("DELETE FROM erp_mrp_feed WHERE MO_ID = ?", MO1);
        jdbc.update("DELETE FROM erp_mrp_reporting WHERE MO_ID = ?", MO1);
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("0"));
        assertEquals(0, voucherCount(MO_NO1), "全零金额不生成凭证");
    }

    // ---------- task 4.3：月度差异转销（BR-4.5-29） ----------

    private long transferCount(String period) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE = 'MO_VAR_TRANSFER' "
                        + "AND SOURCE_DOC_NO = ?", Long.class, period);
        return n == null ? 0 : n;
    }

    @Test
    void varianceTransferMonthlyIdempotent() {
        BomTestAuth.login("plan", "ROLE_PLANNER");
        moService.complete(MO1, new BigDecimal("20"));
        // 完工凭证差异：标准 2000 vs 实际 800 → 差异贷方 1200 → 转销净额 1200
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        FinVoucher tv = voucherService.transferVariance(periodNow());
        assertNotNull(tv, "转销凭证生成");
        assertEquals(1, transferCount(periodNow()), "同期仅一张转销凭证");
        assertEquals("POSTED", tv.getStatus(), "转销生成即 POSTED");
        assertEquals(0, new BigDecimal("1200").compareTo(tv.getTotalDr()), "转销金额 = 差异净额 1200");
        assertEquals(0, tv.getTotalDr().compareTo(tv.getTotalCr()), "借贷平衡");
        Map<String, BigDecimal> byAccount = jdbc.query(
                "SELECT ACCOUNT_CODE, DIRECTION, AMOUNT FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
                        + "WHERE v.SOURCE_TYPE = 'MO_VAR_TRANSFER' AND v.SOURCE_DOC_NO = ?",
                rs -> {
                    Map<String, BigDecimal> m = new java.util.HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getString(1) + "/" + rs.getString(2), rs.getBigDecimal(3));
                    }
                    return m;
                }, periodNow());
        assertEquals(0, new BigDecimal("1200.00").compareTo(byAccount.get("500191/DR")),
                "借生产成本差异清贷方余额");
        assertEquals(0, new BigDecimal("1200.00").compareTo(byAccount.get("6401/CR")),
                "贷主营业务成本（转销售成本默认去向）");
        // 同月幂等 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> voucherService.transferVariance(periodNow()));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("已转销"), ex.getMessage());
        // 越权：计划员不可转销
        BomTestAuth.login("plan", "ROLE_PLANNER");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> voucherService.transferVariance(periodNow())).getCode());
    }

    @Test
    void varianceTransferZeroSkipped() {
        BomTestAuth.login("cost", "ROLE_COST_ACCOUNTANT");
        assertNull(voucherService.transferVariance("209912"), "无差异期次不生成");
        assertEquals(0, transferCount("209912"), "零差异零凭证");
        assertEquals(422, assertThrows(ServiceException.class,
                () -> voucherService.transferVariance("2026X9")).getCode(), "期次格式 422");
    }
}
