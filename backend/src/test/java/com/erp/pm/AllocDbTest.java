package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmAllocService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 间接分摊引擎 DbTest（cost-collection-freeze spec：工时占比 6:4 / 缺规则跳过+L4 /
 * 期间幂等防重）。
 * 造数前缀 cal- / 项目名 CAL%。期间固定用 2026-09（历史期间，不与月度任务撞）。
 */
@SpringBootTest
class AllocDbTest {

    private static final String PERIOD = "2026-09";

    @Autowired
    private PmAllocService allocService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projA;
    private String projB;
    private String projC;

    @BeforeEach
    void setUp() {
        purge();
        projA = seedProject("cal-prj-a", "CAL 项目A");
        projB = seedProject("cal-prj-b", "CAL 项目B");
        projC = seedProject("cal-prj-c", "CAL 项目C（无规则）");
        seedLabor(projA, 600); // 当期工时成本 600
        seedLabor(projB, 400); // 当期工时成本 400 → 占比 6:4
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void hoursBasedSplitSixToFour() {
        allocService.saveRule(Map.of("projectId", projA, "ruleType", "HOURS"));
        allocService.saveRule(Map.of("projectId", projB, "ruleType", "HOURS"));
        String poolId = seedPool(10000);
        Map<String, Object> out = allocService.runPeriod(PERIOD);
        assertEquals(2, out.get("allocatedDetails"), "两项目各一条分摊明细");
        BigDecimal a = amountOf(projA, poolId);
        BigDecimal b = amountOf(projB, poolId);
        assertEquals(0, a.compareTo(new BigDecimal("6000.00")), "A 摊 60%，实际=" + a);
        assertEquals(0, b.compareTo(new BigDecimal("4000.00")), "B 摊 40%，实际=" + b);
        // 归集 INDIRECT 两条（归集日=执行日）
        Integer collect = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE COST_TYPE='INDIRECT' "
                        + "AND SOURCE_TYPE='ALLOC'", Integer.class);
        assertEquals(2, collect);
        // 幂等：重跑不再分摊（同 PERIOD+POOL 已有明细）
        Map<String, Object> again = allocService.runPeriod(PERIOD);
        assertEquals(0, again.get("allocatedDetails"), "同期间同费用池幂等跳过");
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_alloc_detail WHERE PERIOD=?", Integer.class, PERIOD);
        assertEquals(2, total, "重跑不产生新明细");
    }

    @Test
    void missingRuleProjectSkippedAndRescaled() {
        allocService.saveRule(Map.of("projectId", projA, "ruleType", "HOURS"));
        allocService.saveRule(Map.of("projectId", projB, "ruleType", "HOURS"));
        // projC 无规则 → 跳过 + L4 提示；A/B 按 6:4 重算（C 不参与摊派）
        seedPool(5000);
        allocService.runPeriod(PERIOD);
        assertEquals(0, amountOf(projC, null).compareTo(BigDecimal.ZERO), "无规则项目不分摊");
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='PmAllocMissingRule'", Integer.class);
        assertTrue(notices >= 1, "缺规则项目 L4 提示 PMO");
        assertEquals(0, amountOf(projA, null).compareTo(new BigDecimal("3000.00")), "A 按重算占比摊 60%");
        assertEquals(0, amountOf(projB, null).compareTo(new BigDecimal("2000.00")), "B 按重算占比摊 40%");
    }

    @Test
    void budgetRuleUsesBaselineShare() {
        // 预算占比：A 基线 7000 / B 基线 3000 → 7:3
        seedBudget(projA, 7000);
        seedBudget(projB, 3000);
        allocService.saveRule(Map.of("projectId", projA, "ruleType", "BUDGET"));
        allocService.saveRule(Map.of("projectId", projB, "ruleType", "BUDGET"));
        seedPool(1000);
        allocService.runPeriod(PERIOD);
        assertEquals(0, amountOf(projA, null).compareTo(new BigDecimal("700.00")), "A 按预算占比 70%");
        assertEquals(0, amountOf(projB, null).compareTo(new BigDecimal("300.00")), "B 按预算占比 30%");
    }

    @Test
    void noActiveRuleRejectsNothingButSkips() {
        // 完全无规则 → 不分摊、不抛错，提示 PMO
        String poolId = seedPool(800);
        Map<String, Object> out = allocService.runPeriod(PERIOD);
        assertEquals(0, out.get("allocatedDetails"));
        assertEquals(1, out.get("poolCount"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_alloc_detail WHERE POOL_ID=?", Integer.class, poolId));
    }

    @Test
    void invalidRuleTypeRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> allocService.saveRule(Map.of("projectId", projA, "ruleType", "MAGIC")));
        assertEquals(422, e.getCode());
    }

    // ---------- helpers ----------

    private BigDecimal amountOf(String projectId, String poolId) {
        var qw = new StringBuilder(
                "SELECT COALESCE(SUM(AMOUNT),0) FROM erp_pm_alloc_detail WHERE PERIOD=? AND PROJECT_ID=?");
        if (poolId != null) {
            qw.append(" AND POOL_ID=?");
        }
        if (poolId != null) {
            return jdbc.queryForObject(qw.toString(), BigDecimal.class, PERIOD, projectId, poolId);
        }
        return jdbc.queryForObject(qw.toString(), BigDecimal.class, PERIOD, projectId);
    }

    private String seedProject(String id, String name) {
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, '研发', 'INTERNAL', ?, 'cal-emp', '2026-01-01', '2026-12-31', 'ACTIVE', "
                + "'1', 0, 0.10, '0', '0', 'junit', NOW(), '0', 0)", id, name, "NS-" + id);
        return id;
    }

    /** 当期 LABOR 归集（HOURS 规则的基数） */
    private void seedLabor(String projectId, int amount) {
        jdbc.update("INSERT INTO erp_pm_cost_collect (ID, PROJECT_ID, COST_TYPE, AMOUNT, SOURCE_TYPE, "
                + "SOURCE_ID, COLLECT_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'LABOR', ?, 'HOURS_COLLECT', ?, ?, 'junit', NOW(), '0', 0)",
                "cal-hc-" + projectId, projectId, BigDecimal.valueOf(amount),
                "cal-src-" + projectId, PERIOD + "-15");
    }

    private void seedBudget(String projectId, int amount) {
        String wbs = "cal-task-" + projectId;
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, ?, 3, 'CAL 任务', "
                + "'junit', NOW(), '0', 0)", wbs, projectId, "T-" + projectId);
        jdbc.update("INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, "
                + "SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0, 'junit', NOW(), '0', 0)",
                "cal-bl-" + projectId, projectId, wbs, BigDecimal.valueOf(amount));
    }

    private String seedPool(int amount) {
        String id = "cal-pool-1";
        jdbc.update("INSERT INTO erp_pm_cost_pool (ID, PERIOD, EXPENSE_CATEGORY, AMOUNT, REMARK, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, '管理费', ?, 'CAL 测试', "
                + "'junit', NOW(), '0', 0)", id, PERIOD, BigDecimal.valueOf(amount));
        return id;
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pm_alloc_detail WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM erp_pm_alloc_rule WHERE PROJECT_ID LIKE 'cal-%'");
        jdbc.update("DELETE FROM erp_pm_cost_pool WHERE PERIOD=?", PERIOD);
        jdbc.update("DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID LIKE 'cal-%'");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST') t)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'cal-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'cal-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'cal-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'PmAlloc%'");
    }
}
