package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.bi.TempReportService;
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
 * 临时报表库测（add-adhoc-query-console，spec bi-temp-report）。
 * 造数：固定 ID 命名空间 TRP-；@BeforeEach 清场；CREATE_BY 打标物理清理。
 */
@SpringBootTest
class TempReportDbTest {

    @Autowired
    private TempReportService service;
    @Autowired
    private JdbcTemplate jdbc;

    private String tplId;

    @BeforeEach
    void seed() {
        purge();
        SystemAuth.login("trp-creator", "ROLE_BI_DESIGNER");
        tplId = uuid();
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, 'STANDARD', 'SELECT * FROM trp_seed', "
                        + "'[{\"field\":\"ORDER_DATE\",\"role\":\"DIMENSION\",\"timeFlag\":true},"
                        + "{\"field\":\"ENTITY_ID\",\"role\":\"DIMENSION\",\"roleFlag\":true},"
                        + "{\"field\":\"AMOUNT\",\"role\":\"MEASURE\",\"agg\":[\"SUM\"]}]', "
                        + "'[]', 'PUBLISHED', 'trp-test', NOW(), '0', 0)",
                tplId, "TRP-TPL-" + System.nanoTime(), "临时报表测试数据集");
        jdbc.update("DROP TABLE IF EXISTS trp_seed");
        jdbc.update("CREATE TABLE trp_seed (ORDER_DATE varchar(10), ENTITY_ID varchar(32), "
                + "AMOUNT decimal(12,2))");
        jdbc.update("INSERT INTO trp_seed VALUES ('2026-10-01','LE-01',100),('2026-10-02','LE-02',200)");
        // 行级通配绑定（两测试用户均可见全部）
        jdbc.update("INSERT INTO erp_bi_perm_rule (ID, RULE_TYPE, TARGET_KEY, USER_NAME, ENTITY_ID, "
                        + "ACTION, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                        + "('trp-perm-1','ROW_LE','*','trp-creator','*','PASS','ENABLED','trp-test',NOW(),'0',0),"
                        + "('trp-perm-2','ROW_LE','*','trp-guest','*','PASS','ENABLED','trp-test',NOW(),'0',0)");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_temp_report WHERE CREATE_BY LIKE 'trp-%' "
                + "OR CREATE_BY='trp-test'");
        jdbc.update("DELETE FROM erp_bi_perm_rule WHERE CREATE_BY='trp-test'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='TempReportExpire'");
        jdbc.update("DELETE FROM erp_bi_report_template WHERE CREATE_BY='trp-test'");
        jdbc.update("DROP TABLE IF EXISTS trp_seed");
    }

    private static final String QJSON =
            "{\"dimensions\":[\"ENTITY_ID\"],\"measures\":[{\"field\":\"AMOUNT\",\"agg\":\"SUM\"}],"
                    + "\"filters\":[{\"field\":\"ORDER_DATE\",\"op\":\"GE\",\"value\":\"2026-10-01\"},"
                    + "{\"field\":\"ENTITY_ID\",\"op\":\"NE\",\"value\":\"NONE\"}]}";

    private Map<String, Object> saveAs(String name) {
        return service.saveAs(Map.of("name", name, "templateId", tplId, "queryJson", QJSON));
    }

    // ---------- 另存与默认可见 ----------

    @Test
    void saveAsDefaultsToCreatorOnly() {
        Map<String, Object> out = saveAs("华东动销分析");
        assertNotNull(out.get("id"));
        assertNotNull(out.get("expireAt"), "30 天到期时间");
        List<Map<String, Object>> mine = service.list();
        assertTrue(mine.stream().anyMatch(x -> "华东动销分析".equals(x.get("name"))));
        // 他人不可见（未共享）
        SystemAuth.logout();
        SystemAuth.login("trp-guest");
        assertTrue(service.list().isEmpty(), "未共享他人不可见");
    }

    // ---------- 打开按访问者重跑 ----------

    @Test
    void openRerunsAsVisitor() {
        String id = (String) saveAs("重跑测试").get("id");
        // 共享给 guest 后，guest 打开按其身份重跑（spec：共享接收人按自身权限再过滤）
        service.share(id, List.of(Map.of("type", "user", "code", "trp-guest")));
        SystemAuth.logout();
        SystemAuth.login("trp-guest");
        Map<String, Object> data = service.open(id, 1, 50);
        assertNotNull(data.get("headers"), "按访问者身份重跑出结果");
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) data.get("rows");
        assertEquals(2, rows.size(), "行级通配下两行可见");
    }

    @Test
    void openByStrangerRejected() {
        String id = (String) saveAs("越权测试").get("id");
        SystemAuth.logout();
        SystemAuth.login("trp-stranger");
        ServiceException e = assertThrows(ServiceException.class, () -> service.open(id, 1, 50));
        assertEquals(404, e.getCode(), "未授权 404 不暴露存在性");
    }

    // ---------- 共享 ----------

    @Test
    void shareGrantsAccess() {
        String id = (String) saveAs("共享测试").get("id");
        service.share(id, List.of(Map.of("type", "user", "code", "trp-guest")));
        SystemAuth.logout();
        SystemAuth.login("trp-guest");
        List<Map<String, Object>> shared = service.list();
        assertTrue(shared.stream().anyMatch(x -> id.equals(x.get("id"))), "共享后可见");
        Map<String, Object> data = service.open(id, 1, 50);
        assertNotNull(data.get("headers"));
    }

    @Test
    void shareOnlyCreatorCanSet() {
        String id = (String) saveAs("共享权限").get("id");
        SystemAuth.logout();
        SystemAuth.login("trp-guest");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.share(id, List.of()));
        assertEquals(403, e.getCode(), "仅创建人可共享");
    }

    // ---------- 生命周期 ----------

    @Test
    void lifecycleArchivesExpiredAndReminds() {
        String id = (String) saveAs("到期测试").get("id");
        // 回拨到期时间为 2 天后（触发提醒窗口）另一条为已过期
        jdbc.update("UPDATE erp_bi_temp_report SET EXPIRE_AT = DATE_ADD(NOW(), INTERVAL 2 DAY) WHERE ID=?", id);
        String id2 = (String) saveAs("已过期测试").get("id");
        jdbc.update("UPDATE erp_bi_temp_report SET EXPIRE_AT = DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE ID=?", id2);
        Map<String, Object> res = service.sweepLifecycle();
        assertTrue(((Number) res.get("archived")).intValue() >= 1, "到期归档");
        assertTrue(((Number) res.get("reminded")).intValue() >= 1, "前 3 天提醒");
        // 已归档打开 410
        SystemAuth.logout();
        SystemAuth.login("trp-creator", "ROLE_BI_DESIGNER");
        ServiceException e = assertThrows(ServiceException.class, () -> service.open(id2, 1, 50));
        assertEquals(410, e.getCode(), "归档后打开 410");
        // 提醒去重：再扫一次不重复提醒
        Map<String, Object> again = service.sweepLifecycle();
        assertEquals(0, ((Number) again.get("reminded")).intValue(), "提醒去重");
    }

    // ---------- 转正 ----------

    @Test
    void promoteSuccessAndRepeatRejected() {
        String id = (String) saveAs("转正测试").get("id");
        Map<String, Object> out = service.promote(id);
        assertNotNull(out.get("templateId"), "生成模板草稿");
        assertEquals("DRAFT", out.get("templateStatus"), "草稿态进 14.1 流程");
        // 重复转正 422
        ServiceException e = assertThrows(ServiceException.class, () -> service.promote(id));
        assertEquals(422, e.getCode(), "不可重复转正");
        // PROMOTED_TO 回填
        String promoted = jdbc.queryForObject(
                "SELECT PROMOTED_TO FROM erp_bi_temp_report WHERE ID=?", String.class, id);
        assertNotNull(promoted, "关联模板回填");
    }

    @Test
    void promoteByNonCreatorRejected() {
        String id = (String) saveAs("转正权限").get("id");
        SystemAuth.logout();
        SystemAuth.login("trp-guest");
        ServiceException e = assertThrows(ServiceException.class, () -> service.promote(id));
        assertEquals(403, e.getCode(), "仅创建人可转正");
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
