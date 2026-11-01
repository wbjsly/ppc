package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.bi.AdhocQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 即席查询库测（add-adhoc-query-console，spec bi-adhoc-query）。
 * 造数：固定 ID 命名空间 ADH-；@BeforeEach 清场重建；CREATE_BY 打标物理清理。
 */
@SpringBootTest
class AdhocQueryDbTest {

    @Autowired
    private AdhocQueryService service;
    @Autowired
    private com.erp.service.impl.bi.AdhocQueryServiceImpl adhocImpl;
    @Autowired
    private JdbcTemplate jdbc;

    private String tplId;

    @BeforeEach
    void seed() {
        purge();
        SystemAuth.login("adh-tester", "ROLE_BI_DESIGNER");
        // PUBLISHED 数据集模板：语义列（时间/组织/普通维度 + 指标）
        tplId = uuid();
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, 'STANDARD', 'SELECT * FROM erp_bi_adhoc_seed', ?, '[]', "
                        + "'PUBLISHED', 'adh-test', NOW(), '0', 0)",
                tplId, "ADH-TPL-" + System.nanoTime(), "即席测试数据集",
                "[{\"field\":\"ORDER_DATE\",\"label\":\"订单日期\",\"role\":\"DIMENSION\",\"timeFlag\":true},"
                        + "{\"field\":\"ENTITY_ID\",\"label\":\"法人\",\"role\":\"DIMENSION\",\"roleFlag\":true},"
                        + "{\"field\":\"REGION\",\"label\":\"区域\",\"role\":\"DIMENSION\"},"
                        + "{\"field\":\"AMOUNT\",\"label\":\"金额\",\"role\":\"MEASURE\",\"agg\":[\"SUM\",\"AVG\"]}]");
        // 查询源表（治理通道执行的真实数据）
        jdbc.update("DROP TABLE IF EXISTS erp_bi_adhoc_seed");
        jdbc.update("CREATE TABLE erp_bi_adhoc_seed (ORDER_DATE varchar(10), ENTITY_ID varchar(32), "
                + "REGION varchar(32), AMOUNT decimal(12,2))");
        jdbc.update("INSERT INTO erp_bi_adhoc_seed VALUES "
                + "('2026-10-01','LE-01','华东',100),('2026-10-02','LE-01','华北',200),"
                + "('2026-10-03','LE-02','华东',300)");
        // 行级通配绑定（测试用户可见全部；ID 带运行期后缀防残留撞键）
        jdbc.update("INSERT INTO erp_bi_perm_rule (ID, RULE_TYPE, TARGET_KEY, USER_NAME, ENTITY_ID, "
                        + "ACTION, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?,'ROW_LE','*','adh-tester','*','PASS','ENABLED','adh-test',NOW(),'0',0)",
                "adh-perm-" + System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_adhoc_task WHERE CREATE_BY LIKE 'adh-%' "
                + "OR USER_NAME LIKE 'adh-%'");
        jdbc.update("DELETE FROM erp_bi_saved_query WHERE USER_NAME LIKE 'adh-%' "
                + "OR CREATE_BY LIKE 'adh-%'");
        jdbc.update("DELETE FROM erp_bi_perm_rule WHERE CREATE_BY LIKE 'adh-%'");
        jdbc.update("DELETE FROM erp_bi_report_template WHERE CREATE_BY='adh-test'");
        jdbc.update("DROP TABLE IF EXISTS erp_bi_adhoc_seed");
    }

    // ---------- 数据集与字段语义 ----------

    @Test
    void datasetsOnlyPublishedWithSemantics() {
        // 再插一个 DRAFT 模板（不可见）
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('adh-draft','ADH-DRAFT','草稿','STANDARD','SELECT 1','[]','[]',"
                        + "'DRAFT','adh-test',NOW(),'0',0)");
        List<Map<String, Object>> ds = service.datasets();
        assertTrue(ds.stream().anyMatch(d -> tplId.equals(d.get("id"))), "PUBLISHED 可见");
        assertTrue(ds.stream().noneMatch(d -> "adh-draft".equals(d.get("id"))), "DRAFT 不可见");
        Map<String, Object> d = ds.stream().filter(x -> tplId.equals(x.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dims = (List<Map<String, Object>>) d.get("dimensions");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> measures = (List<Map<String, Object>>) d.get("measures");
        assertEquals(3, dims.size(), "三维度");
        assertEquals(1, measures.size(), "一指标");
        assertTrue(dims.stream().anyMatch(x -> Boolean.TRUE.equals(x.get("timeFlag"))), "时间语义");
        assertTrue(dims.stream().anyMatch(x -> Boolean.TRUE.equals(x.get("roleFlag"))), "组织语义");
    }

    @Test
    void legacyColumnFormatCompatible() {
        // 存量纯列名格式 → 归 DIMENSION 不可聚合
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('adh-legacy','ADH-LEGACY','存量','STANDARD','SELECT 1',"
                        + "'[\"COL_A\",\"COL_B\"]','[]','PUBLISHED','adh-test',NOW(),'0',0)");
        List<Map<String, Object>> ds = service.datasets();
        @SuppressWarnings("unchecked")
        Map<String, Object> leg = ds.stream().filter(x -> "adh-legacy".equals(x.get("id")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dims = (List<Map<String, Object>>) leg.get("dimensions");
        assertEquals(2, dims.size(), "存量列归维度");
        assertTrue(dims.stream().noneMatch(x -> "MEASURE".equals(x.get("role"))), "无指标角色");
    }

    // ---------- 预检阻断（BR-4.10-30） ----------

    @Test
    void precheckBlocksMissingTimeAndOrg() {
        // 无时间无组织 → 阻断 + REJECTED 留痕
        String q = "{\"dimensions\":[\"REGION\"],\"measures\":[],"
                + "\"filters\":[{\"field\":\"REGION\",\"op\":\"EQ\",\"value\":\"华东\"}]}";
        ServiceException e = assertThrows(ServiceException.class, () -> service.query(tplId, q));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("时间"), "提示补时间");
        Integer rejected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_adhoc_task WHERE STATUS='REJECTED' "
                        + "AND USER_NAME='adh-tester'", Integer.class);
        assertTrue(rejected >= 1, "REJECTED 留痕（C-4.10-10）");
    }

    @Test
    void precheckBlocksMissingOrgOnly() {
        // 有时间无组织 → 仍阻断
        String q = "{\"dimensions\":[\"REGION\"],\"measures\":[],"
                + "\"filters\":[{\"field\":\"ORDER_DATE\",\"op\":\"GE\",\"value\":\"2026-10-01\"}]}";
        ServiceException e = assertThrows(ServiceException.class, () -> service.query(tplId, q));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("组织"), "提示补组织");
    }

    // ---------- 结构化构建注入防护 ----------

    @Test
    void injectionValueParameterized() {
        // 筛选值注入串 → 参数化绑定为普通字符串（查询正常执行返回 0 行或正常集）
        String q = "{\"dimensions\":[\"REGION\"],\"measures\":[{\"field\":\"AMOUNT\",\"agg\":\"SUM\"}],"
                + "\"filters\":[{\"field\":\"ORDER_DATE\",\"op\":\"GE\",\"value\":\"2026-10-01\"},"
                + "{\"field\":\"ENTITY_ID\",\"op\":\"EQ\",\"value\":\"'; DROP TABLE erp_bi_adhoc_seed; --\"}]}";
        Map<String, Object> res = service.query(tplId, q);
        assertEquals("sync", res.get("mode"), "注入串被参数化，正常执行");
        // 表仍在（未被注入删除）
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM erp_bi_adhoc_seed", Integer.class);
        assertEquals(3, n, "源表未被破坏");
    }

    @Test
    void nonWhitelistFieldRejected() {
        String q = "{\"dimensions\":[\"NOT_A_COL\"],\"measures\":[],"
                + "\"filters\":[{\"field\":\"ORDER_DATE\",\"op\":\"GE\",\"value\":\"2026-01-01\"},"
                + "{\"field\":\"ENTITY_ID\",\"op\":\"EQ\",\"value\":\"x\"}]}";
        ServiceException e = assertThrows(ServiceException.class, () -> service.query(tplId, q));
        assertEquals(422, e.getCode(), "非白名单字段 422");
    }

    // ---------- 同步执行 ----------

    @Test
    void syncQueryAggregatesAndReturns() {
        String q = "{\"dimensions\":[\"REGION\"],\"measures\":[{\"field\":\"AMOUNT\",\"agg\":\"SUM\"}],"
                + "\"filters\":[{\"field\":\"ORDER_DATE\",\"op\":\"GE\",\"value\":\"2026-10-01\"},"
                + "{\"field\":\"ENTITY_ID\",\"op\":\"EQ\",\"value\":\"LE-01\"}]}";
        Map<String, Object> res = service.query(tplId, q);
        assertEquals("sync", res.get("mode"));
        assertNotNull(res.get("headers"));
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) res.get("rows");
        assertEquals(2, rows.size(), "LE-01 两区域各一行（华东/华北）");
        // 同步落 DONE 行（统一计数留痕）
        Integer done = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_adhoc_task WHERE STATUS='DONE' "
                        + "AND USER_NAME='adh-tester'", Integer.class);
        assertEquals(1, done, "同步也落 DONE 行");
    }

    // ---------- 行级权限 ----------

    @Test
    void rowLevelEmptySetRejected() {
        // 换无任何绑定的用户 → 空集 403
        SystemAuth.logout();
        SystemAuth.login("adh-nobody");
        String q = "{\"dimensions\":[\"REGION\"],\"measures\":[],"
                + "\"filters\":[{\"field\":\"ORDER_DATE\",\"op\":\"GE\",\"value\":\"2026-10-01\"},"
                + "{\"field\":\"ENTITY_ID\",\"op\":\"EQ\",\"value\":\"x\"}]}";
        ServiceException e = assertThrows(ServiceException.class, () -> service.query(tplId, q));
        assertEquals(403, e.getCode(), "行级空集默认拒绝");
    }

    // ---------- 配额（BR-4.10-33） ----------

    @Test
    void quotaExhaustedForcesQueue() {
        // 造当日 50 条历史任务（达限）
        for (int i = 0; i < 50; i++) {
            jdbc.update("INSERT INTO erp_bi_adhoc_task (ID, TASK_NO, USER_NAME, TEMPLATE_ID, QUERY_JSON, "
                            + "STATUS, PRIORITY, TRUNCATED, NOTIFY_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, ?, 'adh-tester', ?, '{}', 'DONE', 'NORMAL', '0', '1', 'adh-test', "
                            + "DATE_ADD(NOW(), INTERVAL ? SECOND), '0', 0)",
                    uuid(), "ADHX" + i, tplId, i);
        }
        String q = "{\"dimensions\":[\"REGION\"],\"measures\":[],"
                + "\"filters\":[{\"field\":\"ORDER_DATE\",\"op\":\"GE\",\"value\":\"2026-10-01\"},"
                + "{\"field\":\"ENTITY_ID\",\"op\":\"EQ\",\"value\":\"x\"}]}";
        Map<String, Object> res = service.query(tplId, q);
        assertEquals("queued", res.get("mode"), "超配额强制转后台");
        assertNotNull(res.get("quotaMsg"), "配额用尽提示");
    }

    // ---------- 我的查询 ----------

    @Test
    void savedQueriesRoundTrip() {
        Map<String, Object> payload = Map.of("name", "我的常用", "templateId", tplId,
                "queryJson", "{\"dimensions\":[\"REGION\"]}");
        Map<String, Object> saved = service.saveQuery(payload);
        assertNotNull(saved.get("id"));
        List<Map<String, Object>> list = service.savedQueries();
        assertTrue(list.stream().anyMatch(x -> "我的常用".equals(x.get("name"))));
        // 他人不可见
        SystemAuth.logout();
        SystemAuth.login("adh-other");
        assertTrue(service.savedQueries().isEmpty(), "仅本人可见");
        // 数据集失效校验：DRAFT 模板存在但非 PUBLISHED → 422
        SystemAuth.logout();
        SystemAuth.login("adh-tester", "ROLE_BI_DESIGNER");
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES ('adh-draft2','ADH-DRAFT2','草稿2','STANDARD','SELECT 1','[]','[]',"
                        + "'DRAFT','adh-test',NOW(),'0',0)");
        Map<String, Object> bad = Map.of("name", "x", "templateId", "adh-draft2",
                "queryJson", "{}");
        ServiceException e = assertThrows(ServiceException.class, () -> service.saveQuery(bad));
        assertEquals(422, e.getCode(), "非 PUBLISHED 数据集拒保存");
        // 不存在的数据集 → 404
        ServiceException e404 = assertThrows(ServiceException.class,
                () -> service.saveQuery(Map.of("name", "y", "templateId", "no-such-tpl",
                        "queryJson", "{}")));
        assertEquals(404, e404.getCode(), "不存在数据集 404");
    }

    // ---------- 用量治理 ----------

    @Test
    void governanceShowsQuotaUsage() {
        Map<String, Object> gov = service.governance();
        assertNotNull(gov.get("quota"));
        assertNotNull(gov.get("usedToday"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> usage = (List<Map<String, Object>>) gov.get("usage");
        assertNotNull(usage, "用量汇总可查");
    }

    // ---------- worker 执行（后台任务） ----------

    @Test
    void queuedTaskExecutedByWorkerAsOwner() {
        // 直接入队 + 执行（模拟 worker）
        com.erp.entity.bi.AdhocTask t = new com.erp.entity.bi.AdhocTask();
        t.setId(uuid());
        t.setTaskNo("ADHW" + System.nanoTime());
        t.setUserName("adh-tester");
        t.setTemplateId(tplId);
        t.setQueryJson("{\"dimensions\":[\"REGION\"],\"measures\":[{\"field\":\"AMOUNT\",\"agg\":\"SUM\"}],"
                + "\"filters\":[{\"field\":\"ORDER_DATE\",\"op\":\"GE\",\"value\":\"2026-10-01\"},"
                + "{\"field\":\"ENTITY_ID\",\"op\":\"EQ\",\"value\":\"LE-01\"}]}");
        t.setStatus("QUEUED");
        t.setPriority("SMALL");
        t.setTruncated(false);
        t.setNotifyFlag(false);
        t.setCreateBy("adh-tester");
        jdbc.update("INSERT INTO erp_bi_adhoc_task (ID, TASK_NO, USER_NAME, TEMPLATE_ID, QUERY_JSON, "
                        + "STATUS, PRIORITY, TRUNCATED, NOTIFY_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?,?,?,?,?, ?,?,?,?, ?,NOW(),'0',0)",
                t.getId(), t.getTaskNo(), t.getUserName(), t.getTemplateId(), t.getQueryJson(),
                t.getStatus(), t.getPriority(), "0", "0", t.getCreateBy());
        // worker 直调服务 executeTaskById（登录态即属主）
        adhocImpl.executeTaskById(t.getId());
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_adhoc_task WHERE ID=?", String.class, t.getId());
        assertEquals("DONE", st, "worker 按属主执行完成");
        String res = jdbc.queryForObject(
                "SELECT RESULT_JSON FROM erp_bi_adhoc_task WHERE ID=?", String.class, t.getId());
        assertNotNull(res, "结果集落库");
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
