package com.erp.fin;

import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamDepreciationDetailDao;
import com.erp.dao.eam.EamDepreciationRunDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamDepreciationDetail;
import com.erp.entity.eam.EamDepreciationRun;
import com.erp.service.SysParamService;
import com.erp.service.fin.DepreciationMonitorService;
import com.erp.service.fin.FinPeriodService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-depreciation-monitoring DbTest（spec asset-depreciation-monitoring + gl-management ⑦）。
 * 期间状态三态/凭证反查/明细下钻/实时预警四场景/连续扫描一致/结账⑦三分支。
 */
@SpringBootTest
class DepreciationMonitorDbTest {

    @Autowired
    private DepreciationMonitorService monitorService;
    @Autowired
    private FinPeriodService periodService;
    @Autowired
    private EamAssetCardDao cardDao;
    @Autowired
    private EamDepreciationRunDao runDao;
    @Autowired
    private EamDepreciationDetailDao detailDao;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 隔离期间（未来期，避免与 EAM 折旧测试/冒烟互扰） */
    private static final String P1 = "209911";
    private static final String P2 = "209912";

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID LIKE 'dm-vou-%'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE ID LIKE 'dm-vou-%'");
        jdbc.update("DELETE FROM erp_eam_depreciation_detail WHERE PERIOD_TAG IN ('209911','209912')");
        jdbc.update("DELETE FROM erp_eam_depreciation_run WHERE PERIOD_TAG IN ('209911','209912')");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'DM-%'");
    }

    // ---------- 数据准备 ----------

    /** 造资产卡（必填列全给；残值额默认 原值×残值率） */
    private EamAssetCard card(String no, String status, String deprStatus,
                               String cost, String accum, String impairment,
                               String residualRate, String effectiveDate) {
        EamAssetCard c = new EamAssetCard();
        c.setId("dm-card-" + no);
        c.setCardNo("DM-" + no);
        c.setAssetName("DM 测试资产 " + no);
        c.setCategoryCode("MACH");
        c.setAssetCost(new BigDecimal(cost));
        c.setLifeYears(5);
        c.setResidualRate(new BigDecimal(residualRate));
        c.setResidualValue(new BigDecimal(cost).multiply(new BigDecimal(residualRate))
                .setScale(2, java.math.RoundingMode.HALF_UP));
        c.setDepreciationMethod("STRAIGHT_LINE");
        c.setEffectiveDate(LocalDate.parse(effectiveDate));
        c.setStatus(status);
        c.setDeprStatus(deprStatus);
        c.setAccumDepr(new BigDecimal(accum));
        c.setImpairment(new BigDecimal(impairment));
        c.setLockFlag("0");
        c.setPledgedFlag("0");
        c.setCountFreezeFlag("0");
        c.setLeaseFlag("0");
        cardDao.insert(c);
        return c;
    }

    /** 造折旧运行 */
    private EamDepreciationRun run(String period, String status, String reversedFlag, String totalDepr) {
        EamDepreciationRun r = new EamDepreciationRun();
        r.setId("dm-run-" + period + "-" + status.toLowerCase() + (reversedFlag == null ? "" : "-" + reversedFlag));
        r.setRunNo("DMJZ" + period + status);
        r.setPeriodTag(period);
        r.setStatus(status);
        r.setReversedFlag(reversedFlag);
        r.setTotalDepr(new BigDecimal(totalDepr));
        r.setReconDiff(BigDecimal.ZERO);
        r.setWarningCount(0);
        r.setCreateBy("junit");
        runDao.insert(r);
        return r;
    }

    /** 造凭证（凭证号反查用例） */
    private void voucher(String id, String voucherNo, String period) {
        jdbc.update("INSERT INTO erp_fin_voucher (ID,VOUCHER_NO,VOUCHER_TYPE,VOUCHER_DATE,PERIOD,"
                + "CURRENCY,STATUS,TOTAL_DR,TOTAL_CR,SUMMARY,SOURCE_TYPE,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES (?,?,'DEP',?,?,'CNY','POSTED',100,100,'DM 折旧凭证','DEPRECIATION','junit','0',0)",
                id, voucherNo, period.substring(0, 4) + "-" + period.substring(4) + "-28", period);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemOf(List<Map<String, Object>> items, String period) {
        return items.stream().filter(m -> period.equals(m.get("period"))).findFirst().orElse(null);
    }

    // ---------- 1. 期间状态三态 + 凭证反查 ----------

    @Test
    void periodStatusesThreeStatesWithVoucherLookup() {
        EamDepreciationRun r1 = run(P1, "POSTED", "0", "50000");
        voucher("dm-vou-p1", "DMVCH-209911", P1);
        r1.setVoucherId("dm-vou-p1");
        runDao.updateById(r1);
        run(P2, "REVERSED", "1", "52000");

        List<Map<String, Object>> list = monitorService.periodStatuses();
        Map<String, Object> p1 = itemOf(list, P1);
        assertNotNull(p1, "P1 在列");
        assertEquals("POSTED", p1.get("status"), "P1 已计提");
        assertEquals("DMVCH-209911", p1.get("voucherNo"), "凭证号反查");
        assertEquals(0, new BigDecimal("50000").compareTo((BigDecimal) p1.get("totalDepr")));
        Map<String, Object> p2 = itemOf(list, P2);
        assertNotNull(p2, "P2 在列");
        assertEquals("REVERSED", p2.get("status"), "P2 已冲回");
        // 倒序：P2 在 P1 前
        assertTrue(list.indexOf(p2) < list.indexOf(p1), "期间倒序");
    }

    // ---------- 2. 未计提期间红字标识（当前期无运行） ----------

    @Test
    void currentPeriodWithoutRunMarksNotRun() {
        // 当前期若无运行记录则出现 NOT_RUN 行；有运行则跳过断言（共享库可能有 EAM 冒烟残留）
        String cur = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        Long curRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_depreciation_run WHERE PERIOD_TAG=? AND DEL_FLAG='0'",
                Long.class, cur);
        List<Map<String, Object>> list = monitorService.periodStatuses();
        Map<String, Object> cur0 = itemOf(list, cur);
        if (curRuns == null || curRuns == 0) {
            assertNotNull(cur0, "当前期在列");
            assertEquals("NOT_RUN", cur0.get("status"), "未计提标识");
            assertEquals(Boolean.TRUE, cur0.get("notRun"), "红字标识位");
        } else {
            assertNotEquals("NOT_RUN", cur0 == null ? null : cur0.get("status"), "有运行则非 NOT_RUN");
        }
    }

    // ---------- 3. 明细下钻 ----------

    @Test
    void periodDetailsDrilldown() {
        EamDepreciationDetail d = new EamDepreciationDetail();
        d.setId("dm-det-1");
        d.setRunId("dm-run-x");
        d.setPeriodTag(P1);
        d.setCardId("dm-card-A1");
        d.setCardNo("DM-A1");
        d.setAssetName("DM 下钻资产");
        d.setAssetCost(new BigDecimal("100000"));
        d.setBeginAccumDepr(new BigDecimal("20000"));
        d.setBeginNbv(new BigDecimal("80000"));
        d.setPeriodDepr(new BigDecimal("15000"));
        d.setEndAccumDepr(new BigDecimal("35000"));
        d.setEndNbv(new BigDecimal("65000"));
        d.setResidualAlert("1");
        d.setCapped("0");
        d.setReversed("0");
        d.setCreateBy("junit");
        detailDao.insert(d);

        Map<String, Object> out = monitorService.periodDetails(P1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        assertEquals(1, items.size());
        Map<String, Object> row = items.get(0);
        assertEquals("DM-A1", row.get("cardNo"));
        assertEquals(Boolean.TRUE, row.get("residualAlert"), "明细带预警标记");
        assertEquals(0, new BigDecimal("15000").compareTo((BigDecimal) row.get("periodDepr")));
        assertEquals(0, new BigDecimal("65000").compareTo((BigDecimal) row.get("endNbv")));
    }

    // ---------- 4. 实时预警四场景 ----------

    @Test
    @SuppressWarnings("unchecked")
    void residualAlertsHitMissExclude() {
        // 命中：残值 10000 > NBV 15000×10%=1500（原值 100000 累计 85000 残值率 0.1）
        card("HIT", "IN_USE", "DEPRECIATING", "100000", "85000", "0", "0.1", "2095-01-01");
        // 未达：残值 5000 ≤ NBV 80000×10%=8000
        card("MISS", "IN_USE", "DEPRECIATING", "100000", "20000", "0", "0.05", "2095-01-01");
        // 排除：已处置
        card("DISP", "DISPOSED", "DEPRECIATING", "100000", "85000", "0", "0.1", "2095-01-01");
        // 排除：已提足（累计+减值 ≥ 原值−残值 → 85000+10000 ≥ 100000−10000）
        card("FULL", "IN_USE", "FULLY_DEPRECIATED", "100000", "85000", "10000", "0.1", "2095-01-01");

        Map<String, Object> out = monitorService.residualAlerts();
        List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("items");
        List<String> nos = items.stream().map(m -> String.valueOf(m.get("cardNo"))).toList();
        assertTrue(nos.contains("DM-HIT"), "命中卡在列：" + nos);
        assertFalse(nos.contains("DM-MISS"), "未达阈值不预警");
        assertFalse(nos.contains("DM-DISP"), "已处置排除");
        assertFalse(nos.contains("DM-FULL"), "已提足排除");
        // 占比降序
        Map<String, Object> hit = items.stream()
                .filter(m -> "DM-HIT".equals(m.get("cardNo"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.6667").compareTo((BigDecimal) hit.get("residualRatio")),
                "残值占比 10000/15000≈66.67%");
        assertEquals(0, new BigDecimal("0.10").compareTo((BigDecimal) out.get("threshold")), "缺省阈值 10%");
    }

    // ---------- 5. 连续扫描一致（不落库） ----------

    @Test
    void residualAlertsConsistentAndNoPersist() {
        card("CHK", "IN_USE", "DEPRECIATING", "100000", "85000", "0", "0.1", "2095-01-01");
        Map<String, Object> a = monitorService.residualAlerts();
        long cardCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_asset_card WHERE CARD_NO LIKE 'DM-%'", Long.class);
        Map<String, Object> b = monitorService.residualAlerts();
        assertEquals(a.get("alertCount"), b.get("alertCount"), "两次一致");
        assertEquals(1L, cardCount, "扫描不落库不改卡");
    }

    // ---------- 6. 结账⑦三分支 ----------

    @Test
    @SuppressWarnings("unchecked")
    void checklistSevenBlocksWhenDepreciableButNotRun() {
        // 需计提卡（在用未提足已过起提月）：启用 2099-10 → 起提 209911，P2(209912) 已过起提月
        card("CK1", "IN_USE", "DEPRECIATING", "100000", "20000", "0", "0.1", "2099-10-01");
        Map<String, Object> check = periodService.checklist(P2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> item = items.stream()
                .filter(i -> "pendingDepreciation".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, item.get("ok"), "有需计提卡无运行 → ⑦失败");
        // close 422 且列示
        com.erp.common.ServiceException ex = assertThrows(com.erp.common.ServiceException.class,
                () -> periodService.close(P2));
        assertTrue(ex.getMessage().contains("折旧"), "结账阻断列示折旧：" + ex.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void checklistSevenPassesWithoutDuty() {
        // 199001 古老期：任何卡的起提月都晚于它 → 恒无折旧义务（共享库残留卡不干扰）
        Map<String, Object> check = periodService.checklist("199001");
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> item = items.stream()
                .filter(i -> "pendingDepreciation".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, item.get("ok"), "无折旧义务放行");
        assertTrue(String.valueOf(item.get("detail")).contains("无需计提"), "放行说明");
    }

    @Test
    @SuppressWarnings("unchecked")
    void checklistSevenReversedRunDoesNotCount() {
        // 需计提卡 + 仅 REVERSED 运行 → 仍失败（冲回不算已提）
        card("CK2", "IN_USE", "DEPRECIATING", "100000", "20000", "0", "0.1", "2099-10-01");
        run(P2, "REVERSED", "1", "52000");
        Map<String, Object> check = periodService.checklist(P2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> item = items.stream()
                .filter(i -> "pendingDepreciation".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, item.get("ok"), "REVERSED 不算已提");

        // POSTED 运行后 → 通过
        run(P2, "POSTED", "0", "52000");
        Map<String, Object> check2 = periodService.checklist(P2);
        List<Map<String, Object>> items2 = (List<Map<String, Object>>) check2.get("items");
        Map<String, Object> item2 = items2.stream()
                .filter(i -> "pendingDepreciation".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, item2.get("ok"), "POSTED 后放行");
    }

    // ---------- 7. 起提月边界（启用当月不构成义务） ----------

    @Test
    void cardActivatedThisMonthNoDuty() {
        // 基线相对断言：共享库可能有残留卡——新卡（当月启用，起提 210001）不应使 P2 需计提数增加
        long before = monitorService.countDepreciableCards(P2);
        card("NEW", "IN_USE", "NOT_DEPRECIATED", "100000", "0", "0", "0.1", "2099-12-01");
        long after = monitorService.countDepreciableCards(P2);
        assertEquals(before, after, "启用当月不构成折旧义务（BR-4.13-11）");
    }
}
