package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.bi.QualityCheckService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质量校验库测（add-data-extract-console，spec bi-quality-check，BR-4.10-14）：
 * 三类规则正反例 / 目标存在性校验 / 容差边界 / 手动校验 BATCH_NO 空 /
 * 报告可查（期望/实际值落报告）。
 */
@SpringBootTest
class QualityCheckDbTest {

    @Autowired
    private QualityCheckService service;
    @Autowired
    private JdbcTemplate jdbc;

    /** 测试专用目标表（确定性造数，teardown 撤除） */
    private static final String DEMO = "erp_bi_qc_demo";

    @BeforeEach
    void setUp() {
        purge();
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + DEMO
                + " (ID INT PRIMARY KEY, AMOUNT DECIMAL(18,2) NULL, NAME VARCHAR(64) NULL)");
        jdbc.update("DELETE FROM " + DEMO);
        jdbc.update("INSERT INTO " + DEMO + " (ID, AMOUNT, NAME) VALUES (1, 100.00, 'a'),"
                + "(2, 200.00, 'b'),(3, NULL, NULL)");
        SystemAuth.login("qc-tester", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
        jdbc.execute("DROP TABLE IF EXISTS " + DEMO);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_quality_result WHERE RULE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_bi_quality_rule WHERE NAME LIKE 'QC-%') t)");
        jdbc.update("DELETE FROM erp_bi_quality_rule WHERE NAME LIKE 'QC-%'");
    }

    private Map<String, Object> rule(String name, String type) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", "QC-" + name);
        p.put("ruleType", type);
        return p;
    }

    // ---------- 目标存在性与标识符校验（spec 规则配置化需求） ----------

    @Test
    void ruleTargetMustExistAndBeIdent() {
        Map<String, Object> p = rule("不存在表", "NOT_NULL");
        p.put("targetTable", "not_exist_table_qq");
        p.put("targetColumn", "AMOUNT");
        ServiceException e = assertThrows(ServiceException.class, () -> service.createRule(p));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());

        Map<String, Object> p2 = rule("非法标识", "NOT_NULL");
        p2.put("targetTable", DEMO + "; DROP TABLE x");
        p2.put("targetColumn", "AMOUNT");
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.createRule(p2));
        assertEquals(422, e2.getCode(), "标识符白名单：" + e2.getMessage());
    }

    // ---------- NOT_NULL（BR-4.10-14 完整性） ----------

    @Test
    void notNullPassAndFail() {
        // AMOUNT 非空率 = 2/3 ≈ 66.667% < 99.9% → FAIL（SEVERE）
        Map<String, Object> p = rule("NOTNULL-FAIL", "NOT_NULL");
        p.put("targetTable", DEMO);
        p.put("targetColumn", "AMOUNT");
        p.put("notNullMin", "99.9");
        p.put("severeLevel", "SEVERE");
        String id = (String) service.createRule(p).get("id");
        Map<String, Object> r = service.runNow(id);
        assertEquals("FAIL", r.get("conclusion"), "非空率低于阈值 FAIL：" + r.get("actual"));
        assertTrue(String.valueOf(r.get("actual")).contains("66.6"), String.valueOf(r.get("actual")));

        // ID 列全非空 100% ≥ 99.9% → PASS
        Map<String, Object> p2 = rule("NOTNULL-PASS", "NOT_NULL");
        p2.put("targetTable", DEMO);
        p2.put("targetColumn", "ID");
        p2.put("notNullMin", "99.9");
        String id2 = (String) service.createRule(p2).get("id");
        assertEquals("PASS", service.runNow(id2).get("conclusion"));
    }

    // ---------- RECONCILE 容差边界（spec 一致性） ----------

    @Test
    void reconcileToleranceBoundary() {
        // 差值 0.0005 ≤ 容差 0.001 → PASS
        Map<String, Object> p = rule("REC-PASS", "RECONCILE");
        p.put("leftSql", "SELECT 1.0000");
        p.put("rightSql", "SELECT 1.0005");
        p.put("tolerance", "0.001");
        String id = (String) service.createRule(p).get("id");
        Map<String, Object> r = service.runNow(id);
        assertEquals("PASS", r.get("conclusion"), "容差内 PASS：" + r.get("actual"));
        assertTrue(String.valueOf(r.get("actual")).contains("0.0005"), String.valueOf(r.get("actual")));

        // 差值 1 > 容差 0 → FAIL；勾稽 SQL 非 SELECT → 422
        Map<String, Object> p2 = rule("REC-FAIL", "RECONCILE");
        p2.put("leftSql", "SELECT 1");
        p2.put("rightSql", "SELECT 2");
        p2.put("tolerance", "0");
        String id2 = (String) service.createRule(p2).get("id");
        assertEquals("FAIL", service.runNow(id2).get("conclusion"));

        Map<String, Object> p3 = rule("REC-BADSQL", "RECONCILE");
        p3.put("leftSql", "DELETE FROM " + DEMO);
        p3.put("rightSql", "SELECT 1");
        ServiceException e = assertThrows(ServiceException.class, () -> service.createRule(p3));
        assertEquals(422, e.getCode(), "勾稽 SQL 也走 SqlGuard：" + e.getMessage());
    }

    // ---------- RANGE（spec 准确性） ----------

    @Test
    void rangeDetectsOutOfBounds() {
        // AMOUNT 含 NULL + 范围 0~150：NULL 不计入越界（SQL 比较 NULL 为假），200 越界 1 行 → FAIL
        Map<String, Object> p = rule("RANGE-FAIL", "RANGE");
        p.put("targetTable", DEMO);
        p.put("targetColumn", "AMOUNT");
        p.put("minVal", "0");
        p.put("maxVal", "150");
        String id = (String) service.createRule(p).get("id");
        Map<String, Object> r = service.runNow(id);
        assertEquals("FAIL", r.get("conclusion"), "越界行 FAIL：" + r.get("actual"));
        assertTrue(String.valueOf(r.get("actual")).contains("1"), String.valueOf(r.get("actual")));

        // 0~1000 → 全在界内 PASS
        Map<String, Object> p2 = rule("RANGE-PASS", "RANGE");
        p2.put("targetTable", DEMO);
        p2.put("targetColumn", "AMOUNT");
        p2.put("minVal", "0");
        p2.put("maxVal", "1000");
        String id2 = (String) service.createRule(p2).get("id");
        assertEquals("PASS", service.runNow(id2).get("conclusion"));
        // 未登记上下限 → 422
        Map<String, Object> p3 = rule("RANGE-NOBOUND", "RANGE");
        p3.put("targetTable", DEMO);
        p3.put("targetColumn", "AMOUNT");
        ServiceException e = assertThrows(ServiceException.class, () -> service.createRule(p3));
        assertEquals(422, e.getCode());
    }

    // ---------- 报告可查（spec 校验报告需求） ----------

    @Test
    void resultsQueryableWithExpectation() {
        Map<String, Object> p = rule("REPORT", "NOT_NULL");
        p.put("targetTable", DEMO);
        p.put("targetColumn", "AMOUNT");
        p.put("notNullMin", "99.9");
        p.put("severeLevel", "WARN");
        String id = (String) service.createRule(p).get("id");
        service.runNow(id);

        // 手动校验 BATCH_NO 为空
        List<Map<String, Object>> rows = service.listResults(null, null, null);
        Map<String, Object> row = rows.stream()
                .filter(x -> String.valueOf(x.get("ruleId")).equals(id)).findFirst().orElseThrow();
        assertEquals("FAIL", row.get("conclusion"));
        assertNotNull(row.get("expected"), "期望值落报告");
        assertNotNull(row.get("actual"), "实际值落报告");
        assertEquals("WARN", row.get("severeLevel"), "级别快照");
        assertTrue(String.valueOf(row.get("batchNo")).equals("null")
                || row.get("batchNo") == null, "手动校验无批次号");
    }

    // ---------- 停用后不参与批次执行 ----------

    @Test
    void disabledRuleExcludedFromBatchRun() {
        Map<String, Object> p = rule("BATCH-SCOPE", "RECONCILE");
        p.put("leftSql", "SELECT 1");
        p.put("rightSql", "SELECT 2");
        p.put("severeLevel", "SEVERE");
        String id = (String) service.createRule(p).get("id");
        // 停用 → 批次执行不计（severeFail=0）
        service.updateRule(id, Map.of("status", "DISABLED"));
        int severe = service.runForBatch("QC-NOBATCH-" + System.nanoTime(), List.of());
        assertEquals(0, severe, "停用规则不参与批次执行");
    }
}
