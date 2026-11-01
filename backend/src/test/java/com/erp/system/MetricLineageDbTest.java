package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.bi.MetricDictService;
import com.erp.service.bi.MetricLineageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标血缘库测（add-metric-dict-console，spec bi-metric-lineage，S-4.10-15）：
 * 三来源采集（上游表/衍生指标/引用报表）/ LIKE 误匹配防护 /
 * 快照幂等与不可变 / 影响报告高风险标注 / 历史版本回溯 / 手工绑定。
 */
@SpringBootTest
class MetricLineageDbTest {

    @Autowired
    private MetricLineageService lineageService;
    @Autowired
    private MetricDictService dictService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SystemAuth.login("mln-designer", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_metric_lineage WHERE METRIC_KEY LIKE 'M-MLN-%'");
        jdbc.update("DELETE FROM erp_bi_metric_dict WHERE METRIC_KEY LIKE 'M-MLN-%'");
        jdbc.update("DELETE FROM erp_bi_report_template WHERE CODE LIKE 'MLN%'");
    }

    /** 造数：上游两表 + 引用另一已注册指标（衍生） */
    private void seed(String key, String formula) {
        Map<String, Object> p = new HashMap<>();
        p.put("metricKey", key);
        p.put("name", "血缘指标 " + key);
        p.put("definition", "血缘库测");
        p.put("formula", formula);
        p.put("timeDim", "DAY");
        p.put("srcMapping", "{\"Invoice.amount\":\"amt\",\"JournalEntry.cost\":\"cost\"}");
        p.put("formulaType", "RATIO");
        p.put("numerator", "n");
        p.put("denominator", "d");
        dictService.register(p);
        dictService.publish(key, formula);
    }

    private void seedTpl(String code, String metricsJson, String remark) {
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, REMARK, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?,?,?,'STANDARD','SELECT 1','[]',?,"
                        + "'PUBLISHED',?,'mln-test',NOW(),'0',0)",
                uuid(), code, "血缘模板 " + code, metricsJson, remark);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> view(List<Map<String, Object>> rows, String type) {
        return rows.stream().filter(r -> type.equals(r.get("nodeType")))
                .findFirst().orElseThrow();
    }

    // ---------------- 三来源采集 ----------------

    @Test
    void threeSourceCollection() {
        seed("M-MLN-001", "{\"expr\":\"SUM(AMOUNT)\"}");
        // 依赖指标（衍生源）先注册
        seed("M-MLN-002", "{\"expr\":\"SUM(X)\"}");
        // 引用报表（正常引用 + RISK:HIGH 标注）
        seedTpl("MLN-A", "[{\"metricKey\":\"M-MLN-001\",\"formula\":\"{}\"}]", null);
        seedTpl("MLN-B", "[{\"metricKey\":\"M-MLN-001\",\"formula\":\"{}\"}]", "RISK:HIGH 对外披露");

        // M-MLN-002 formula 引用 M-MLN-001 → 采集前把 001 编码写进 002 的公式使其成为衍生
        //（此处反转：001 的 formula 不含他键，改由 001 依赖 002——重新登记 001 含 002 编码）
        Map<String, Object> p = new HashMap<>();
        p.put("metricKey", "M-MLN-001");
        p.put("name", "血缘指标 M-MLN-001");
        p.put("definition", "血缘库测");
        p.put("formula", "{\"expr\":\"SUM(AMOUNT) - M-MLN-002\"}");
        p.put("timeDim", "DAY");
        p.put("srcMapping", "{\"Invoice.amount\":\"amt\",\"JournalEntry.cost\":\"cost\"}");
        p.put("formulaType", "RATIO");
        p.put("numerator", "n");
        p.put("denominator", "d");
        // 已发布 → 走变更申请拿新版；直接改当前行更快：删掉重来
        jdbc.update("DELETE FROM erp_bi_metric_dict WHERE METRIC_KEY='M-MLN-001'");
        dictService.register(p);
        dictService.publish("M-MLN-001", "{\"expr\":\"SUM(AMOUNT) - M-MLN-002\"}");

        lineageService.collectAndSnapshot("M-MLN-001", 1);
        Map<String, Object> g = lineageService.graph("M-MLN-001");
        assertEquals(2, ((List<?>) g.get("upstreamTables")).size(), "两张上游表");
        List<Map<String, Object>> derived = (List<Map<String, Object>>) g.get("derivedMetrics");
        assertEquals(1, derived.size(), "衍生指标 M-MLN-002");
        assertEquals("M-MLN-002", derived.get(0).get("nodeId"));
        List<Map<String, Object>> reports = (List<Map<String, Object>>) g.get("reports");
        assertEquals(2, reports.size(), "两张引用报表");
    }

    @Test
    void likeFalsePositiveExcludedByExactParse() {
        seed("M-MLN-010", "{\"expr\":\"SUM(A)\"}");
        // 模板引用 M-MLN-0102（非 M-MLN-010）——LIKE 粗筛会命中，精确解析须排除
        seedTpl("MLN-FP", "[{\"metricKey\":\"M-MLN-0102\",\"formula\":\"{}\"}]", null);
        lineageService.collectAndSnapshot("M-MLN-010", 1);
        Map<String, Object> g = lineageService.graph("M-MLN-010");
        assertEquals(0, ((List<?>) g.get("reports")).size(), "误匹配模板被精确解析排除");
    }

    // ---------------- 快照幂等与不可变 ----------------

    @Test
    void snapshotIdempotentAndImmutable() {
        seed("M-MLN-020", "{\"expr\":\"SUM(A)\"}");
        seedTpl("MLN-S1", "[{\"metricKey\":\"M-MLN-020\",\"formula\":\"{}\"}]", null);
        lineageService.collectAndSnapshot("M-MLN-020", 1);
        // 幂等：同版本重跑不重复
        lineageService.collectAndSnapshot("M-MLN-020", 1);
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_metric_lineage WHERE METRIC_KEY='M-MLN-020' "
                        + "AND LINEAGE_VERSION=1", Integer.class);
        assertEquals(3, n, "幂等：两上游表 + 一引用报表，无重复行");

        // 快照后新增引用报表 → v1 快照不变（不可变），v2 才反映
        seedTpl("MLN-S2", "[{\"metricKey\":\"M-MLN-020\",\"formula\":\"{}\"}]", null);
        lineageService.collectAndSnapshot("M-MLN-020", 2);
        Map<String, Object> v1 = lineageService.graphVersion("M-MLN-020", 1);
        List<Map<String, Object>> v1Reports = (List<Map<String, Object>>) v1.get("reports");
        assertEquals(1, v1Reports.size(), "v1 快照保持采集时点（仅 S1）");
        assertTrue(String.valueOf(v1Reports.get(0).get("nodeName")).contains("MLN-S1"));
        Map<String, Object> v2 = lineageService.graphVersion("M-MLN-020", 2);
        List<Map<String, Object>> v2Reports = (List<Map<String, Object>>) v2.get("reports");
        assertEquals(2, v2Reports.size(), "v2 快照含后增报表 S2");
        assertTrue(v2Reports.stream().anyMatch(r ->
                String.valueOf(r.get("nodeName")).contains("MLN-S2")));
    }

    // ---------------- 影响报告与回溯 ----------------

    @Test
    void impactReportHighlightsHighRisk() {
        seed("M-MLN-030", "{\"expr\":\"SUM(A)\"}");
        seedTpl("MLN-CORE", "[{\"metricKey\":\"M-MLN-030\",\"formula\":\"{}\"}]", "RISK:HIGH 驾驶舱");
        seedTpl("MLN-NORM", "[{\"metricKey\":\"M-MLN-030\",\"formula\":\"{}\"}]", null);
        lineageService.collectAndSnapshot("M-MLN-030", 1);
        Map<String, Object> rep = lineageService.impactReport("M-MLN-030", 1);
        List<Map<String, Object>> hr = (List<Map<String, Object>>) rep.get("highRiskReports");
        assertEquals(1, hr.size(), "高风险报表 1 张");
        assertTrue(String.valueOf(hr.get(0).get("nodeName")).contains("MLN-CORE"));
        assertTrue(String.valueOf(rep.get("summary")).contains("引用报表"), "摘要：" + rep.get("summary"));
        // 历史回溯：不存在的版本 404
        assertThrows(ServiceException.class,
                () -> lineageService.graphVersion("M-MLN-030", 9));
    }

    // ---------------- 手工绑定 ----------------

    @Test
    void manualBindIdempotent() {
        seed("M-MLN-040", "{\"expr\":\"SUM(A)\"}");
        Map<String, Object> b = lineageService.bind("M-MLN-040", "METRIC",
                "M-MLN-041", "衍生指标41");
        assertEquals(Boolean.FALSE, b.get("duplicated"));
        // 重复绑定幂等跳过
        Map<String, Object> b2 = lineageService.bind("M-MLN-040", "METRIC",
                "M-MLN-041", "衍生指标41");
        assertEquals(Boolean.TRUE, b2.get("duplicated"));
        // REPORT 类型拒绝（只能自动采集）
        ServiceException e = assertThrows(ServiceException.class,
                () -> lineageService.bind("M-MLN-040", "REPORT", "x", "y"));
        assertEquals(422, e.getCode());
        Map<String, Object> g = lineageService.graph("M-MLN-040");
        assertEquals(1, ((List<?>) g.get("derivedMetrics")).size(), "手工绑定入图");
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
