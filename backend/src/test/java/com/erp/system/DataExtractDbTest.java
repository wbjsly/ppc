package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.bi.DataExtractService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据抽取库测（add-data-extract-console，spec bi-extract-console）：
 * 数据源登记与连接加密（FR-4.10-2-1）/ schema 变更检测暂停（BR-4.10-15）/
 * 仅绑已发布模板 / 增量水位字段校验 / DAG 挂起 / 波动暂停确认（BR-4.10-12）/
 * 重试耗尽告警（BR-4.10-13）/ 就绪标志与 SEVERE 质量阻断（BR-4.10-14）。
 */
@SpringBootTest
class DataExtractDbTest {

    @Autowired
    private DataExtractService service;
    @Autowired
    private com.erp.service.bi.QualityCheckService qualityCheckService;
    @Autowired
    private JdbcTemplate jdbc;

    private String dsId;
    private String tplId;

    @BeforeEach
    void setUp() {
        purge();
        SystemAuth.login("ext-tester", "ROLE_BI_DESIGNER", "ROLE_ADMIN");
        dsId = createDs("EXT-DS");
        tplId = seedTemplate("EXT-TPL-1", "SELECT 1 AS AMOUNT",
                "[{\"field\":\"AMOUNT\",\"title\":\"金额\"}]");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_extract_run WHERE BATCH_NO LIKE 'EXT%' "
                + "OR CONFIG_ID IN (SELECT ID FROM (SELECT ID FROM erp_bi_extract_config "
                + "WHERE CODE LIKE 'EXT%') t)");
        jdbc.update("DELETE FROM erp_bi_extract_batch WHERE BATCH_NO LIKE 'EXT%' "
                + "OR BATCH_DATE >= ?", LocalDate.now().minusDays(10));
        jdbc.update("DELETE FROM erp_bi_extract_config WHERE CODE LIKE 'EXT%' "
                + "OR NAME LIKE 'EXT%'");
        jdbc.update("DELETE FROM erp_bi_datasource WHERE NAME LIKE 'EXT%'");
        jdbc.update("DELETE FROM erp_bi_report_template WHERE CODE LIKE 'EXT%'");
        jdbc.update("DELETE FROM erp_bi_quality_result WHERE RULE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_bi_quality_rule WHERE NAME LIKE 'EXT%') t)");
        jdbc.update("DELETE FROM erp_bi_quality_rule WHERE NAME LIKE 'EXT%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'DataExtract%' "
                + "OR BIZ_TYPE LIKE 'Quality%'");
    }

    private String createDs(String name) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("type", "DB");
        p.put("conn", "jdbc:mysql://secret-host:3306/erp?user=sa&password=topsecret");
        p.put("remark", "EXT 测试数据源");
        return (String) service.createDatasource(p).get("id");
    }

    private String seedTemplate(String code, String sql, String columnsJson) {
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,?,'[]','PUBLISHED',"
                        + "'ext-test',NOW(),'0',0)",
                id, code, "测试模板 " + code, "STANDARD", sql, columnsJson);
        return id;
    }

    private String createConfig(String code, String tpl, String mode, String wm, int seq) {
        Map<String, Object> p = new HashMap<>();
        p.put("code", code);
        p.put("name", "EXT 配置 " + code);
        p.put("datasourceId", dsId);
        p.put("templateId", tpl);
        p.put("mode", mode);
        p.put("watermarkField", wm);
        p.put("seq", seq);
        return (String) service.createConfig(p).get("id");
    }

    // ---------- 数据源（FR-4.10-2-1） ----------

    @Test
    void datasourceConnEncryptedNotPlaintext() {
        Map<String, Object> ds = service.listDatasources().stream()
                .filter(d -> dsId.equals(d.get("id"))).findFirst().orElseThrow();
        assertEquals("ACTIVE", ds.get("status"));
        String masked = String.valueOf(ds.get("connMasked"));
        assertTrue(masked.contains("****"), "连接信息脱敏展示：" + masked);
        // 库内为密文，明文不出库
        String enc = jdbc.queryForObject(
                "SELECT CONN_ENC FROM erp_bi_datasource WHERE ID=?", String.class, dsId);
        assertNotNull(enc);
        assertTrue(!enc.contains("topsecret"), "存库为密文：" + enc);
        // 连通性测试通过（本库可达 + 登记名核对）
        Map<String, Object> tc = service.testConnection(dsId);
        assertEquals(Boolean.TRUE, tc.get("ok"), "连通性：" + tc.get("msg"));
    }

    @Test
    void duplicateDatasourceNameRejected() {
        Map<String, Object> p = new HashMap<>();
        p.put("name", "EXT-DS");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.createDatasource(p));
        assertEquals(422, e.getCode());
    }

    // ---------- schema 变更检测（BR-4.10-15） ----------

    @Test
    void schemaChangePausesConfigs() {
        // 篡改快照制造差异 → 检测 → SCHEMA_CHANGED
        jdbc.update("UPDATE erp_bi_datasource SET SCHEMA_SNAPSHOT=? WHERE ID=?",
                "{\"tables\":{}}", dsId);
        Map<String, Object> r = service.checkSchema(dsId);
        assertEquals(Boolean.TRUE, r.get("changed"), "空快照必有差异：" + r);
        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_datasource WHERE ID=?", String.class, dsId);
        assertEquals("SCHEMA_CHANGED", status);
        // 变更通知已广播 BI 设计师
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='DataExtractSchemaChange'",
                Integer.class);
        assertTrue(n >= 1, "schema 变更通知");
        // 恢复快照 → ACTIVE
        service.refreshSnapshot(dsId);
        status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_datasource WHERE ID=?", String.class, dsId);
        assertEquals("ACTIVE", status);
    }

    // ---------- 配置校验 ----------

    @Test
    void configRejectsUnpublishedTemplate() {
        String draftId = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,?,'[]','DRAFT',"
                        + "'ext-test',NOW(),'0',0)",
                draftId, "EXT-TPL-DRAFT", "草稿模板", "STANDARD", "SELECT 1",
                "[{\"field\":\"AMOUNT\"}]");
        Map<String, Object> p = new HashMap<>();
        p.put("code", "EXT-CFG-DRAFT");
        p.put("name", "EXT 绑草稿");
        p.put("datasourceId", dsId);
        p.put("templateId", draftId);
        ServiceException e = assertThrows(ServiceException.class, () -> service.createConfig(p));
        assertEquals(422, e.getCode(), "仅已发布模板可绑定：" + e.getMessage());
        assertTrue(e.getMessage().contains("已发布"), e.getMessage());
    }

    @Test
    void incrementalWatermarkMustBeInColumns() {
        Map<String, Object> p = new HashMap<>();
        p.put("code", "EXT-CFG-WM");
        p.put("name", "EXT 水位校验");
        p.put("datasourceId", dsId);
        p.put("templateId", tplId);
        p.put("mode", "INCREMENTAL");
        p.put("watermarkField", "NOT_IN_COLUMNS");
        ServiceException e = assertThrows(ServiceException.class, () -> service.createConfig(p));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("水位字段"), e.getMessage());
    }

    @Test
    void incrementalWatermarkAdvancesOnRun() {
        createConfig("EXT-CFG-INC", tplId, "INCREMENTAL", "AMOUNT", 10);
        Map<String, Object> out = service.runBatch(LocalDate.now(), true);
        assertEquals("READY", out.get("batchStatus"), "就绪：" + out);
        String wm = jdbc.queryForObject(
                "SELECT LAST_WATERMARK FROM erp_bi_extract_config WHERE CODE='EXT-CFG-INC'",
                String.class);
        assertEquals("1", wm, "水位推进（MAX over 治理查询）");
        // 二次执行幂等（批次已 READY）
        Map<String, Object> again = service.runBatch(LocalDate.now(), true);
        assertEquals("already-ready", again.get("note"));
    }

    // ---------- DAG 挂起（spec 批次需求） ----------

    @Test
    void dagHoldsDownstreamWhenUpstreamNotReady() {
        createConfig("EXT-CFG-A", tplId, "FULL", null, 10);
        createConfig("EXT-CFG-B", tplId, "FULL", null, 20);
        // 直接造当日批次：上游 seq10 置 PAUSED（波动暂停未确认）
        String batchNo = LocalDate.now().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_bi_extract_batch (ID, BATCH_NO, BATCH_DATE, STATUS, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,?,?,?)",
                uuid(), batchNo, LocalDate.now(), "RUNNING", "ext-test", java.sql.Timestamp.valueOf(
                        java.time.LocalDateTime.now()), "0", 0);
        String cfgA = jdbc.queryForObject(
                "SELECT ID FROM erp_bi_extract_config WHERE CODE='EXT-CFG-A'", String.class);
        String cfgB = jdbc.queryForObject(
                "SELECT ID FROM erp_bi_extract_config WHERE CODE='EXT-CFG-B'", String.class);
        jdbc.update("INSERT INTO erp_bi_extract_run (ID, BATCH_DATE, BATCH_NO, CONFIG_ID, SEQ, "
                        + "STATUS, RETRY, MANUAL_CONFIRM, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?,?,?,?,?, 'PAUSED', 0, 0, 'ext-test', NOW(), '0', 0)",
                uuid(), LocalDate.now(), batchNo, cfgA, 10);
        jdbc.update("INSERT INTO erp_bi_extract_run (ID, BATCH_DATE, BATCH_NO, CONFIG_ID, SEQ, "
                        + "STATUS, RETRY, MANUAL_CONFIRM, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?,?,?,?,?, 'PENDING', 0, 0, 'ext-test', NOW(), '0', 0)",
                uuid(), LocalDate.now(), batchNo, cfgB, 20);

        // 自动模式（窗口外仍可推进既有批次）：上游 PAUSED 未确认 → 批次停在上游，下游挂起
        Map<String, Object> out = service.runBatch(LocalDate.now(), false);
        assertEquals("advanced", out.get("note"), "推进既有批次：" + out);
        String stA = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_extract_run WHERE CONFIG_ID=? AND BATCH_NO=?",
                String.class, cfgA, batchNo);
        assertEquals("PAUSED", stA, "上游保持暂停待确认");
        String stB = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_extract_run WHERE CONFIG_ID=? AND BATCH_NO=?",
                String.class, cfgB, batchNo);
        assertEquals("PENDING", stB, "上游未 READY 下游挂起");
    }

    // ---------- 波动暂停与人工确认（BR-4.10-12） ----------

    @Test
    void fluctuationPauseThenConfirm() {
        String cfg = createConfig("EXT-CFG-FLU", tplId, "FULL", null, 10);
        // 前日 READY 基准 5 行；本批模板 SQL 返回 >50 行 → 波动 >10 倍暂停
        String bigSql = "SELECT TABLE_NAME AS AMOUNT FROM information_schema.TABLES";
        String bigTpl = seedTemplate("EXT-TPL-BIG", bigSql,
                "[{\"field\":\"AMOUNT\",\"title\":\"金额\"}]");
        jdbc.update("UPDATE erp_bi_extract_config SET TEMPLATE_ID=? WHERE ID=?", bigTpl, cfg);
        LocalDate d1 = LocalDate.now().minusDays(1);
        String b1 = d1.toString().replace("-", "");
        jdbc.update("INSERT INTO erp_bi_extract_batch (ID, BATCH_NO, BATCH_DATE, STATUS, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,?,?,?)",
                uuid(), b1, d1, "READY", "ext-test", java.sql.Timestamp.valueOf(
                        java.time.LocalDateTime.now()), "0", 0);
        jdbc.update("INSERT INTO erp_bi_extract_run (ID, BATCH_DATE, BATCH_NO, CONFIG_ID, SEQ, "
                        + "STATUS, ROW_COUNT, RETRY, MANUAL_CONFIRM, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?,?,?,?,?, 'READY', 5, 0, 0, "
                        + "'ext-test', NOW(), '0', 0)",
                uuid(), d1, b1, cfg, 10);

        Map<String, Object> out = service.runBatch(LocalDate.now(), true);
        assertEquals("advanced", out.get("note"));
        List<Map<String, Object>> runs = (List<Map<String, Object>>) out.get("runs");
        Map<String, Object> run = runs.stream()
                .filter(r -> cfg.equals(r.get("configId"))).findFirst().orElseThrow();
        assertEquals("PAUSED", run.get("status"), "波动 >10 倍暂停：" + run.get("errorMsg"));
        assertTrue(String.valueOf(run.get("errorMsg")).contains("波动"), String.valueOf(run.get("errorMsg")));
        // 告警广播数据治理专员
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='DataExtractFluctuation'",
                Integer.class);
        assertTrue(n >= 1, "波动告警通知");

        // 人工确认继续 → 下次执行放行 → READY
        String runId = (String) run.get("id");
        service.confirmRun(runId);
        Map<String, Object> out2 = service.runBatch(LocalDate.now(), true);
        runs = (List<Map<String, Object>>) out2.get("runs");
        run = runs.stream().filter(r -> cfg.equals(r.get("configId"))).findFirst().orElseThrow();
        assertEquals("READY", run.get("status"), "确认后继续执行");
    }

    // ---------- 重试耗尽（BR-4.10-13） ----------

    @Test
    void retryExhaustedFailsWithAlert() {
        String badTpl = seedTemplate("EXT-TPL-BAD", "SELECT * FROM not_exist_table_xyz",
                "[{\"field\":\"AMOUNT\"}]");
        createConfig("EXT-CFG-BAD", badTpl, "FULL", null, 10);
        // 连续 3 次推进 → RETRY 耗尽 FAILED
        for (int i = 0; i < 3; i++) {
            service.runBatch(LocalDate.now(), true);
        }
        String st = jdbc.queryForObject(
                "SELECT r.STATUS FROM erp_bi_extract_run r JOIN erp_bi_extract_config c "
                        + "ON c.ID=r.CONFIG_ID WHERE c.CODE='EXT-CFG-BAD' AND r.BATCH_DATE=?",
                String.class, LocalDate.now());
        assertEquals("FAILED", st, "重试耗尽转人工");
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='DataExtractFailed'",
                Integer.class);
        assertTrue(n >= 1, "失败告警通知");
    }

    // ---------- 就绪标志与质量阻断（BR-4.10-14） ----------

    @Test
    void severeQualityFailureBlocksReadyThenRecheckUnblocks() {
        createConfig("EXT-CFG-SEV", tplId, "FULL", null, 10);
        // SEVERE 勾稽必失败（1 vs 2，容差 0）
        Map<String, Object> rp = new HashMap<>();
        rp.put("name", "EXT-勾稽必失败");
        rp.put("ruleType", "RECONCILE");
        rp.put("datasourceId", dsId);
        rp.put("leftSql", "SELECT 1");
        rp.put("rightSql", "SELECT 2");
        rp.put("tolerance", "0");
        rp.put("severeLevel", "SEVERE");
        String ruleId = (String) qualityCheckService.createRule(rp).get("id");

        service.runBatch(LocalDate.now(), true);
        String batchNo = LocalDate.now().toString().replace("-", "");
        String batchStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_extract_batch WHERE BATCH_NO=?",
                String.class, batchNo);
        assertEquals("QUALITY_BLOCKED", batchStatus, "SEVERE 失败阻断就绪");
        Map<String, Object> ready = service.readySnapshot();
        assertEquals(Boolean.FALSE, ready.get("ready"), "阻断期间不就绪");
        // 报告落库：FAIL + SEVERE
        Integer fails = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_quality_result WHERE BATCH_NO=? "
                        + "AND CONCLUSION='FAIL' AND SEVERE_LEVEL='SEVERE'",
                Integer.class, batchNo);
        assertTrue(fails >= 1, "校验报告记录 FAIL");
        // 修复（停用规则）→ 复验 → 解除阻断置 READY
        qualityCheckService.updateRule(ruleId, Map.of("status", "DISABLED"));
        Map<String, Object> rc = qualityCheckService.recheckBlockedBatch(batchNo);
        assertEquals("READY", rc.get("status"), "复验无 SEVERE FAIL 自动就绪：" + rc);
    }

    @Test
    void warnFailureDoesNotBlockReady() {
        createConfig("EXT-CFG-WARN", tplId, "FULL", null, 10);
        // WARN 勾稽失败 → 告警放行，批次仍 READY
        Map<String, Object> rp = new HashMap<>();
        rp.put("name", "EXT-勾稽WARN");
        rp.put("ruleType", "RECONCILE");
        rp.put("datasourceId", dsId);
        rp.put("leftSql", "SELECT 1");
        rp.put("rightSql", "SELECT 2");
        rp.put("tolerance", "0");
        rp.put("severeLevel", "WARN");
        qualityCheckService.createRule(rp);

        service.runBatch(LocalDate.now(), true);
        String batchNo = LocalDate.now().toString().replace("-", "");
        String batchStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_extract_batch WHERE BATCH_NO=?",
                String.class, batchNo);
        assertEquals("READY", batchStatus, "WARN 失败告警放行");
    }

    // ---------- helpers ----------

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
