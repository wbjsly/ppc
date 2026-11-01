package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.bi.KpiService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 经营看板库测（add-kpi-dashboard，spec bi-kpi-dashboard）。
 * 造数：固定 ID 命名空间 KPD-；@BeforeEach 清场；CREATE_BY 打标物理清理防幽灵键。
 */
@SpringBootTest
class KpiDashboardDbTest {

    @Autowired
    private KpiService service;
    @Autowired
    private JdbcTemplate jdbc;

    private String tplId;

    @BeforeEach
    void seed() {
        purge();
        SystemAuth.login("kpd-tester", "ROLE_BI_DESIGNER");
        // PUBLISHED 下钻模板（挂 14.1 语义）
        tplId = uuid();
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, 'STANDARD', 'SELECT * FROM kpd_seed', "
                        + "'[{\"field\":\"REGION\",\"role\":\"DIMENSION\"}]', '[]', 'PUBLISHED', "
                        + "'kpd-test', NOW(), '0', 0)",
                tplId, "KPD-TPL-" + System.nanoTime(), "KPI下钻模板");
        // KPI 取数源表（确定性数据：销售额 1000）
        jdbc.update("DROP TABLE IF EXISTS kpd_seed");
        jdbc.update("CREATE TABLE kpd_seed (REGION varchar(32), AMT decimal(14,2))");
        jdbc.update("INSERT INTO kpd_seed VALUES ('华东', 600), ('华北', 400)");
        // 行级通配绑定（KPI OWNER=kpd-tester，allowedEntities 空集会 403 拒绝快照）
        jdbc.update("INSERT INTO erp_bi_perm_rule (ID, RULE_TYPE, TARGET_KEY, USER_NAME, "
                + "ENTITY_ID, ACTION, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'ROW_LE', '*', 'kpd-tester', '*', 'PASS', 'ENABLED', "
                + "'kpd-test', NOW(), '0', 0)", "kpd-perm-" + System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_kpi_def WHERE CREATE_BY LIKE 'kpd-%'");
        jdbc.update("DELETE FROM erp_bi_kpi_target WHERE CREATE_BY LIKE 'kpd-%'");
        jdbc.update("DELETE FROM erp_bi_kpi_snapshot WHERE CREATE_BY LIKE 'kpd-%' "
                + "OR CREATE_BY='kpi-scheduler'");
        jdbc.update("DELETE FROM erp_bi_kpi_alert WHERE CREATE_BY LIKE 'kpd-%' "
                + "OR CREATE_BY='kpi-scheduler'");
        jdbc.update("DELETE FROM erp_bi_perm_rule WHERE CREATE_BY='kpd-test'");
        jdbc.update("DELETE FROM erp_bi_report_template WHERE CREATE_BY='kpd-test'");
        jdbc.update("DROP TABLE IF EXISTS kpd_seed");
    }

    private static final String SQL_GLOBAL =
            "SELECT 'KPD-T' AS kpi_key, '' AS dim_value, CURDATE() AS period_date, "
                    + "COALESCE(SUM(AMT),0) AS actual FROM kpd_seed";
    private static final String SQL_DIM =
            "SELECT 'KPD-D' AS kpi_key, REGION AS dim_value, CURDATE() AS period_date, "
                    + "SUM(AMT) AS actual FROM kpd_seed GROUP BY REGION";

    private Map<String, Object> mkDef(String kpiKey, String sql, String targetMode,
                                      BigDecimal fixed, String dimKey, String drill) {
        return service.saveDef(Map.of(
                "kpiKey", kpiKey, "name", "测试KPI-" + kpiKey, "sqlText", sql,
                "targetMode", targetMode, "owner", "kpd-tester",
                "dimKey", dimKey == null ? "" : dimKey,
                "drillTemplateId", drill == null ? "" : drill,
                "fixedTarget", fixed == null ? BigDecimal.ZERO : fixed));
    }

    // ---------- 2.1 定义 CRUD ----------

    @Test
    void defCrudWithValidation() {
        Map<String, Object> d = mkDef("KPD-T", SQL_GLOBAL, "PERIODIC", null, null, null);
        assertNotNull(d.get("id"));
        assertEquals(0, new BigDecimal("0.8").compareTo((BigDecimal) d.get("thresholdWarn")),
                "阈值缺省 0.8");
        assertEquals(0, new BigDecimal("0.5")
                .compareTo((BigDecimal) d.get("thresholdCritical")), "阈值缺省 0.5");
        // KPI_KEY 唯一
        ServiceException dup = assertThrows(ServiceException.class,
                () -> mkDef("KPD-T", SQL_GLOBAL, "PERIODIC", null, null, null));
        assertEquals(422, dup.getCode());
        // 非 SELECT SQL 422
        ServiceException bad = assertThrows(ServiceException.class,
                () -> mkDef("KPD-BAD", "DELETE FROM kpd_seed", "PERIODIC", null, null, null));
        assertEquals(422, bad.getCode());
        // 未注册 metric_key 422（C-4.10-01）
        ServiceException m = assertThrows(ServiceException.class, () -> service.saveDef(Map.of(
                "kpiKey", "KPD-M", "name", "x", "sqlText", SQL_GLOBAL,
                "metricKey", "NOT-REG-XYZ", "targetMode", "PERIODIC", "owner", "kpd-tester")));
        assertEquals(422, m.getCode());
        // 下钻模板非 PUBLISHED 422
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('kpd-draft','KPD-DRAFT','草稿','STANDARD','SELECT 1','[]','[]',"
                + "'DRAFT','kpd-test',NOW(),'0',0)");
        ServiceException t = assertThrows(ServiceException.class,
                () -> mkDef("KPD-D", SQL_GLOBAL, "PERIODIC", null, null, "kpd-draft"));
        assertEquals(422, t.getCode());
        // 列表 + 删除
        List<Map<String, Object>> defs = service.listDefs();
        assertTrue(defs.stream().anyMatch(x -> "KPD-T".equals(x.get("kpiKey"))));
        service.deleteDef(String.valueOf(d.get("id")));
        assertTrue(service.listDefs().stream().noneMatch(x -> "KPD-T".equals(x.get("kpiKey"))));
    }

    // ---------- 2.2 目标维护 ----------

    @Test
    void targetUpsertByPeriod() {
        mkDef("KPD-T", SQL_GLOBAL, "PERIODIC", null, null, null);
        String period = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        service.saveTarget(Map.of("kpiKey", "KPD-T", "period", period,
                "targetValue", new BigDecimal("1200")));
        // upsert 同期覆盖
        service.saveTarget(Map.of("kpiKey", "KPD-T", "period", period,
                "targetValue", new BigDecimal("1500")));
        List<Map<String, Object>> tgts = service.listTargets("KPD-T");
        assertEquals(1, tgts.size(), "同期 upsert 不叠加");
        assertEquals(0, new BigDecimal("1500")
                .compareTo((BigDecimal) tgts.get(0).get("targetValue")));
    }

    // ---------- 3.1 快照计算 ----------

    @Test
    void snapshotUpsertIdempotentAndTargetBinding() {
        mkDef("KPD-T", SQL_GLOBAL, "PERIODIC", null, null, null);
        String period = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        service.saveTarget(Map.of("kpiKey", "KPD-T", "period", period,
                "targetValue", new BigDecimal("1200")));
        Map<String, Object> r1 = service.runSnapshot();
        assertTrue(((Number) r1.get("success")).intValue() >= 1);
        // actual=1000, target=1200 → ratio 0.8333 ≥ threshold_warn 0.8 → ACHIEVED 绿
        Map<String, Object> snap = jdbc.queryForMap(
                "SELECT ACTUAL, TARGET, RATIO, STATUS FROM erp_bi_kpi_snapshot "
                        + "WHERE KPI_KEY='KPD-T' AND SNAP_DATE=CURDATE() AND DEL_FLAG='0'");
        assertEquals(0, new BigDecimal("1000").compareTo((BigDecimal) snap.get("ACTUAL")));
        assertEquals(0, new BigDecimal("1200").compareTo((BigDecimal) snap.get("TARGET")));
        assertEquals("ACHIEVED", snap.get("STATUS"), "0.8333 ≥0.8 → ACHIEVED 绿");
        // 重跑幂等（不叠加行）
        service.runSnapshot();
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_bi_kpi_snapshot "
                + "WHERE KPI_KEY='KPD-T' AND SNAP_DATE=CURDATE() AND DEL_FLAG='0'", Long.class);
        assertEquals(1L, cnt, "重跑覆盖不叠加");
    }

    @Test
    void snapshotThreeColorByThreshold() {
        // 绿：target=800 → ratio 1.25 ≥0.8 ACHIEVED
        mkDef("KPD-G", SQL_GLOBAL, "PERIODIC", null, null, null);
        service.saveTarget(Map.of("kpiKey", "KPD-G", "period",
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                "targetValue", new BigDecimal("800")));
        // 黄：target=1500 → ratio 0.6667 在 [0.5,0.8) WARN
        mkDef("KPD-W", SQL_GLOBAL, "PERIODIC", null, null, null);
        service.saveTarget(Map.of("kpiKey", "KPD-W", "period",
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                "targetValue", new BigDecimal("1500")));
        // 红：target=2500 → ratio 0.4 <0.5 BELOW
        mkDef("KPD-R", SQL_GLOBAL, "PERIODIC", null, null, null);
        service.saveTarget(Map.of("kpiKey", "KPD-R", "period",
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                "targetValue", new BigDecimal("2500")));
        service.runSnapshot();
        String g = jdbc.queryForObject("SELECT STATUS FROM erp_bi_kpi_snapshot "
                + "WHERE KPI_KEY='KPD-G' AND SNAP_DATE=CURDATE() AND DEL_FLAG='0'", String.class);
        assertEquals("ACHIEVED", g, "绿");
        String w = jdbc.queryForObject("SELECT STATUS FROM erp_bi_kpi_snapshot "
                + "WHERE KPI_KEY='KPD-W' AND SNAP_DATE=CURDATE() AND DEL_FLAG='0'", String.class);
        assertEquals("WARN", w, "黄");
        String r = jdbc.queryForObject("SELECT STATUS FROM erp_bi_kpi_snapshot "
                + "WHERE KPI_KEY='KPD-R' AND SNAP_DATE=CURDATE() AND DEL_FLAG='0'", String.class);
        assertEquals("BELOW", r, "红");
    }

    @Test
    void snapshotNoTargetNoStatus() {
        mkDef("KPD-NT", SQL_GLOBAL, "PERIODIC", null, null, null); // 无目标
        service.runSnapshot();
        Map<String, Object> snap = jdbc.queryForMap(
                "SELECT RATIO, STATUS FROM erp_bi_kpi_snapshot "
                        + "WHERE KPI_KEY='KPD-NT' AND SNAP_DATE=CURDATE() AND DEL_FLAG='0'");
        assertNull(snap.get("RATIO"), "无目标 RATIO NULL");
        assertNull(snap.get("STATUS"), "无目标不着色");
    }

    @Test
    void singleKpiFailureDoesNotBlock() {
        mkDef("KPD-OK", SQL_GLOBAL, "PERIODIC", null, null, null);
        // 坏 KPI：引用不存在表 → 失败但不阻断
        mkDef("KPD-FAIL", "SELECT 'KPD-FAIL' AS kpi_key, '' AS dim_value, "
                + "CURDATE() AS period_date, 1 AS actual FROM no_such_table_xyz",
                "PERIODIC", null, null, null);
        Map<String, Object> r = service.runSnapshot();
        assertTrue(((Number) r.get("failed")).intValue() >= 1, "坏 KPI 记失败");
        assertTrue(((Number) r.get("success")).intValue() >= 1, "好 KPI 不被阻断");
        // 失败留痕
        String err = jdbc.queryForObject("SELECT ERROR_MSG FROM erp_bi_kpi_snapshot "
                + "WHERE KPI_KEY='KPD-FAIL' AND SNAP_DATE=CURDATE() AND DEL_FLAG='0'",
                String.class);
        assertNotNull(err, "失败留痕");
    }

    @Test
    void dimensionKpiSplitsRows() {
        mkDef("KPD-D", SQL_DIM, "PERIODIC", null, "REGION", null);
        service.saveTarget(Map.of("kpiKey", "KPD-D", "period",
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                "targetValue", new BigDecimal("500")));
        service.runSnapshot();
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_bi_kpi_snapshot "
                + "WHERE KPI_KEY='KPD-D' AND SNAP_DATE=CURDATE() AND DEL_FLAG='0'",
                Long.class);
        assertEquals(2L, cnt, "华东/华北各一行");
    }

    // ---------- 4.1 达成概览 ----------

    @Test
    void summaryThreeColorAndDrillFlag() {
        mkDef("KPD-T", SQL_GLOBAL, "PERIODIC", null, null, tplId); // 挂下钻
        service.saveTarget(Map.of("kpiKey", "KPD-T", "period",
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                "targetValue", new BigDecimal("1200")));
        service.runSnapshot();
        List<Map<String, Object>> cards = service.summary();
        Map<String, Object> c = cards.stream()
                .filter(x -> "KPD-T".equals(x.get("kpiKey"))).findFirst().orElseThrow();
        assertEquals("ACHIEVED", c.get("status"), "三色判定（0.8333≥0.8 绿）");
        assertEquals(Boolean.TRUE, c.get("hasDrill"), "挂模板可下钻");
        assertEquals(tplId, c.get("drillTemplateId"));
    }

    // ---------- 4.2 趋势 ----------

    @Test
    void trendReturnsSeriesWithTarget() {
        mkDef("KPD-T", SQL_GLOBAL, "PERIODIC", null, null, null);
        service.saveTarget(Map.of("kpiKey", "KPD-T", "period",
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                "targetValue", new BigDecimal("1200")));
        service.runSnapshot();
        Map<String, Object> t = service.trend("KPD-T", "", 30);
        assertNotNull(t.get("target"), "含目标参考线值");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> series = (List<Map<String, Object>>) t.get("series");
        assertEquals(1, series.size(), "当日一条快照");
    }

    // ---------- 3.3 预警（连续 N 天） ----------

    @Test
    void alertTriggersAfterConsecutiveDaysAndRecovers() {
        mkDef("KPD-T", SQL_GLOBAL, "PERIODIC", null, null, null);
        String period = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        // 目标 2000 → ratio 0.5 < 1 连续低于
        service.saveTarget(Map.of("kpiKey", "KPD-T", "period", period,
                "targetValue", new BigDecimal("2000")));
        // 造近 3 日快照（ratio 0.5 连续低于）
        for (int i = 2; i >= 0; i--) {
            jdbc.update("INSERT INTO erp_bi_kpi_snapshot (ID, KPI_KEY, DIM_VALUE, SNAP_DATE, "
                            + "ACTUAL, TARGET, RATIO, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'KPD-T', '', DATE_SUB(CURDATE(), INTERVAL ? DAY), "
                            + "1000, 2000, 0.5, 'BELOW', 'kpd-tester', NOW(), '0', 0)",
                    uuid(), i);
        }
        // 今日跑快照（仍低于）→ 触发
        service.runSnapshot();
        Map<String, Object> alert = jdbc.queryForMap(
                "SELECT STATUS, DAYS_BREACHED FROM erp_bi_kpi_alert "
                        + "WHERE KPI_KEY='KPD-T' AND DEL_FLAG='0'");
        assertEquals("ACTIVE", alert.get("STATUS"), "连续低于触发");
        // 幂等：再跑一次 → DAYS_BREACHED 累加不重复 INSERT
        service.runSnapshot();
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_bi_kpi_alert "
                + "WHERE KPI_KEY='KPD-T' AND DEL_FLAG='0'", Long.class);
        assertEquals(1L, cnt, "预警 upsert 幂等不重复");
        // 回达标：改目标为 500 → ratio 2 ≥1 → RECOVERED
        jdbc.update("UPDATE erp_bi_kpi_target SET TARGET_VALUE=500 WHERE KPI_KEY='KPD-T'");
        service.runSnapshot();
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_bi_kpi_alert "
                + "WHERE KPI_KEY='KPD-T' AND DEL_FLAG='0'", String.class);
        assertEquals("RECOVERED", st, "回达标自动恢复");
        // 预警列表 API
        List<Map<String, Object>> active = service.alerts("ACTIVE");
        assertTrue(active.stream().noneMatch(x -> "KPD-T".equals(x.get("kpiKey"))),
                "恢复后 ACTIVE 列表移除");
    }

    // ---------- 内置种子 ----------

    @Test
    void builtinSeedsPresent() {
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_bi_kpi_def "
                + "WHERE KPI_KEY LIKE 'KPI-%' AND DEL_FLAG='0'", Long.class);
        assertTrue(cnt >= 6, "六项内置 KPI 种子（" + cnt + "）");
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
