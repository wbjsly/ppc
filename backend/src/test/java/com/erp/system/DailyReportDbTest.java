package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.SysParamService;
import com.erp.service.bi.DailyReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日报订阅库测（add-daily-report-subscription，spec bi-daily-report）。
 * 造数：CREATE_BY 打标 dr- 命名空间物理清理；行级通配绑定防 allowedEntities 空集拒生成。
 */
@SpringBootTest
class DailyReportDbTest {

    @Autowired
    private DailyReportService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SysParamService sysParam;

    private static final String KPI = "DR-T-KPI";

    @BeforeEach
    void seed() {
        purge();
        SystemAuth.login("dr-tester", "ROLE_BI_DESIGNER");
        // KPI 定义（14.7 语义，saveDef 校验 kpiKey 存在）
        jdbc.update("INSERT INTO erp_bi_kpi_def (ID, KPI_KEY, NAME, SQL_TEXT, STATUS, OWNER, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, 'DR测试KPI', 'SELECT 1', 'ENABLED', 'dr-tester', "
                        + "'dr-test', NOW(), '0', 0)",
                uuid(), KPI);
        // 行级通配绑定（订阅/生成需非空 allowedEntities）
        jdbc.update("INSERT INTO erp_bi_perm_rule (ID, RULE_TYPE, TARGET_KEY, USER_NAME, ENTITY_ID, "
                        + "ACTION, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, 'ROW_LE', '*', 'dr-tester', '*', 'PASS', 'ENABLED', "
                        + "'dr-test', NOW(), '0', 0)", "dr-perm-" + System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        sysParam.setValue("REPORT_READY_WAIT_MIN", "120", "INT", "BI", "就绪等待", "dr-test");
        sysParam.setValue("REPORT_SIMULATE_FAIL", "", "STRING", "BI", "模拟失败通道", "dr-test");
        jdbc.update("DELETE FROM erp_sys_param WHERE PARAM_KEY='REPORT_SIMULATE_FAIL'");
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_daily_report_def WHERE CREATE_BY LIKE 'dr-%'");
        jdbc.update("DELETE FROM erp_bi_daily_report_sub WHERE CREATE_BY LIKE 'dr-%'");
        jdbc.update("DELETE FROM erp_bi_report_push_log WHERE CREATE_BY LIKE 'dr-%' "
                + "OR CREATE_BY='daily-report'");
        jdbc.update("DELETE FROM erp_bi_report_receipt WHERE CREATE_BY LIKE 'dr-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'DailyReport%'");
        jdbc.update("DELETE FROM erp_bi_kpi_def WHERE CREATE_BY='dr-test'");
        jdbc.update("DELETE FROM erp_bi_kpi_snapshot WHERE CREATE_BY='dr-test'");
        jdbc.update("DELETE FROM erp_bi_perm_rule WHERE CREATE_BY='dr-test'");
    }

    // ---- 日报定义 ----

    @Test
    void defSaveRejectsUnregisteredKpi() {
        ServiceException e = assertThrows(ServiceException.class, () -> service.saveDef(Map.of(
                "code", "DR-DEF-X", "name", "未注册指标日报",
                "metricKeysJson", "[{\"kpiKey\":\"DR-NO-SUCH\",\"title\":\"x\"}]")));
        assertEquals(422, e.getCode(), "未注册 KPI 422");
    }

    @Test
    void defSaveAndListRoundTrip() {
        String id = mkDef();
        Map<String, Object> d = service.getDef(id);
        assertEquals("DR-DEF-1", d.get("code"));
        assertTrue(service.listDefs().stream().anyMatch(x -> id.equals(x.get("id"))));
        // 存在 ACTIVE 订阅时删除被拒
        service.saveSubscription(Map.of("reportId", id, "channelsJson", "[\"NOTICE\"]"));
        ServiceException e = assertThrows(ServiceException.class, () -> service.deleteDef(id));
        assertEquals(422, e.getCode(), "有 ACTIVE 订阅拒删");
    }

    // ---- 订阅（BR-4.10-35） ----

    @Test
    void subscriptionExcludesAllWhenNoRowPermission() {
        String id = mkDef();
        SystemAuth.logout();
        SystemAuth.login("dr-noperm");
        Map<String, Object> r = service.saveSubscription(Map.of(
                "reportId", id, "channelsJson", "[\"NOTICE\"]"));
        @SuppressWarnings("unchecked")
        List<String> excluded = (List<String>) r.get("excludedKpis");
        assertEquals(List.of(KPI), excluded, "无行级权限 → 指标全部自动剔除");
        // L4 提示已发（订阅本身不失败）
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sys_notice "
                + "WHERE BIZ_TYPE='DailyReportSubExcl' AND TARGET_USER='dr-noperm'", Integer.class);
        assertTrue(n != null && n > 0, "剔除提示已发");
        // 订阅仍落库（不整单失败）
        Integer sub = jdbc.queryForObject("SELECT COUNT(*) FROM erp_bi_daily_report_sub "
                + "WHERE USER_NAME='dr-noperm' AND STATUS='ACTIVE' AND DEL_FLAG='0'", Integer.class);
        assertEquals(1, sub, "订阅本身不失败");
    }

    @Test
    void unsubscribeDisablesWithTrace() {
        String id = mkDef();
        String subId = (String) service.saveSubscription(Map.of(
                "reportId", id, "channelsJson", "[\"NOTICE\"]")).get("id");
        service.unsubscribe(subId);
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_daily_report_sub WHERE ID=?", String.class, subId);
        assertEquals("DISABLED", st, "退订置 DISABLED 留痕");
    }

    // ---- 推送主流程 ----

    @Test
    void pushAllGeneratesAndSends() {
        String id = mkDef();
        String subId = (String) service.saveSubscription(Map.of(
                "reportId", id, "channelsJson", "[\"NOTICE\"]")).get("id");
        seedSnapshot(LocalDate.now());
        // pushNow 按定义隔离（pushAll 会推库中全部启用定义，受并行测试数据影响）
        Map<String, Object> r = service.pushNow(id);
        assertEquals(Boolean.TRUE, r.get("ready"));
        assertEquals(1, ((Number) r.get("generated")).intValue(), "生成一份");
        assertEquals(1, ((Number) r.get("sent")).intValue(), "站内信送达");
        Map<String, Object> log = jdbc.queryForMap(
                "SELECT SEND_STATUS, DATA_CUTOFF, PDF_REF FROM erp_bi_report_push_log "
                        + "WHERE SUB_ID=? AND CHANNEL='NOTICE'", subId);
        assertEquals("SENT", log.get("SEND_STATUS"));
        assertNotNull(log.get("PDF_REF"), "PDF 引用落库");
        // LAST_PUSH_DATE 回填
        Object lpd = jdbc.queryForObject(
                "SELECT LAST_PUSH_DATE FROM erp_bi_daily_report_sub WHERE ID=?", Object.class, subId);
        assertNotNull(lpd, "订阅最近推送日回填");
    }

    @Test
    void pushAllTimeoutNotifiesAndEscalates() {
        String id = mkDef();
        String subId = (String) service.saveSubscription(Map.of(
                "reportId", id, "channelsJson", "[\"NOTICE\"]")).get("id");
        sysParam.setValue("REPORT_READY_WAIT_MIN", "0", "INT", "BI", "就绪等待=0（测试）", "dr-test");
        Map<String, Object> r = service.pushAll(LocalDate.now().plusDays(3));
        assertEquals(Boolean.FALSE, r.get("ready"), "未就绪");
        assertEquals(Boolean.TRUE, r.get("timedOut"), "超时");
        // 订阅人通知 + 运维工单
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sys_notice "
                + "WHERE BIZ_TYPE='DailyReportNotReady' AND TARGET_USER='dr-tester'", Integer.class);
        assertTrue(n != null && n > 0, "订阅人收到未就绪通知");
        Integer ops = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sys_notice "
                + "WHERE BIZ_TYPE='DailyReportEscalate' AND TARGET_ROLE='ROLE_ADMIN'", Integer.class);
        assertTrue(ops != null && ops > 0, "运维工单已生成");
    }

    @Test
    void degradeWhenBothChannelsFail() {
        String id = mkDef();
        String subId = (String) service.saveSubscription(Map.of(
                "reportId", id, "channelsJson", "[\"MAIL\",\"WECOM\"]")).get("id");
        seedSnapshot(LocalDate.now());
        sysParam.setValue("REPORT_SIMULATE_FAIL", "MAIL,WECOM", "STRING", "BI", "模拟双通道失败", "dr-test");
        Map<String, Object> r = service.pushNow(id);
        assertEquals(0, ((Number) r.get("sent")).intValue(), "双通道无直接送达");
        assertEquals(1, ((Number) r.get("degraded")).intValue(), "降级站内信一次");
        // MAIL/WECOM 各 FAILED + NOTICE DEGRADED（降级标记）
        Map<String, Object> mail = jdbc.queryForMap(
                "SELECT SEND_STATUS, RETRY_CNT AS retryCnt FROM erp_bi_report_push_log "
                        + "WHERE SUB_ID=? AND CHANNEL='MAIL'", subId);
        assertEquals("FAILED", mail.get("SEND_STATUS"));
        assertEquals(3, ((Number) mail.get("retryCnt")).intValue(), "独立重试 3 次");
        Map<String, Object> dec = jdbc.queryForMap(
                "SELECT SEND_STATUS, DEGRADED_FLAG FROM erp_bi_report_push_log "
                        + "WHERE SUB_ID=? AND CHANNEL='NOTICE'", subId);
        assertEquals("DEGRADED", dec.get("SEND_STATUS"));
        assertEquals("Y", dec.get("DEGRADED_FLAG"), "降级标记");
        // 运维工单
        Integer ops = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sys_notice "
                + "WHERE BIZ_TYPE='DailyReportChannelFail'", Integer.class);
        assertTrue(ops != null && ops > 0, "双通道失败工单");
    }

    // ---- 回执与统计 ----

    @Test
    void markReadUpsertsReceiptAndChecksOwner() {
        String logId = seedPushLog("dr-tester");
        service.markRead(logId);
        service.markRead(logId);
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT OPEN_CNT, OPEN_AT FROM erp_bi_report_receipt WHERE PUSH_LOG_ID=?", logId);
        assertEquals(2, ((Number) r.get("OPEN_CNT")).intValue(), "打开次数累计");
        assertNotNull(r.get("OPEN_AT"));
        // 非接收人 403
        SystemAuth.logout();
        SystemAuth.login("dr-guest");
        ServiceException e = assertThrows(ServiceException.class, () -> service.markRead(logId));
        assertEquals(403, e.getCode(), "仅本人可记回执");
    }

    @Test
    void statsCountsMonthPushes() {
        seedPushLog("dr-tester");
        Map<String, Object> s = service.stats();
        assertNotNull(s.get("period"));
        assertTrue(((Number) s.get("total")).longValue() >= 1, "当月推送计入总数");
    }

    @Test
    void scanUnreadPromptsOncePerWindow() {
        String subId = seedSub("dr-tester");
        jdbc.update("UPDATE erp_bi_daily_report_sub SET LAST_PUSH_DATE=DATE_SUB(CURDATE(), "
                + "INTERVAL 31 DAY) WHERE ID=?", subId);
        int n = service.scanUnreadAndPrompt();
        assertEquals(1, n, "30 天未阅读 → L4 提示一次");
        Integer notice = jdbc.queryForObject("SELECT COUNT(*) FROM erp_sys_notice "
                + "WHERE BIZ_TYPE='DailyReportUnread' AND TARGET_USER='dr-tester'", Integer.class);
        assertEquals(1, notice, "提示已发");
        // 第二次扫描去重（LAST_PROMPT_AT 刚置）
        int again = service.scanUnreadAndPrompt();
        assertEquals(0, again, "30 天窗口内去重不重复提示");
        // 已阅读的不提示
        jdbc.update("UPDATE erp_bi_daily_report_sub SET LAST_PROMPT_AT=NULL, "
                + "LAST_READ_AT=NOW() WHERE ID=?", subId);
        assertEquals(0, service.scanUnreadAndPrompt(), "已阅读不提示");
    }

    // ---- 工具 ----

    private String mkDef() {
        Map<String, Object> r = service.saveDef(Map.of(
                "code", "DR-DEF-1", "name", "DR测试日报",
                "metricKeysJson", "[{\"kpiKey\":\"" + KPI + "\",\"title\":\"DR测试KPI\"}]",
                "pushTime", "07:30"));
        return (String) r.get("id");
    }

    private void seedSnapshot(LocalDate d) {
        jdbc.update("INSERT INTO erp_bi_kpi_snapshot (ID, KPI_KEY, DIM_VALUE, SNAP_DATE, ACTUAL, "
                        + "TARGET, RATIO, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, '', ?, 100, 120, 0.8333, 'WARN', 'dr-test', NOW(), '0', 0)",
                uuid(), KPI, d);
    }

    private String seedSub(String user) {
        String defId = mkDef();
        String id = (String) service.saveSubscription(Map.of(
                "reportId", defId, "channelsJson", "[\"NOTICE\"]")).get("id");
        jdbc.update("UPDATE erp_bi_daily_report_sub SET USER_NAME=? WHERE ID=?", user, id);
        return id;
    }

    private String seedPushLog(String receiver) {
        String subId = seedSub(receiver);
        String logId = uuid();
        jdbc.update("INSERT INTO erp_bi_report_push_log (ID, SUB_ID, REPORT_CODE, PUSH_DATE, "
                        + "RECEIVER, CHANNEL, SEND_STATUS, RETRY_CNT, DEGRADED_FLAG, DATA_CUTOFF, "
                        + "QUALITY_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, 'DR-DEF-1', CURDATE(), ?, 'NOTICE', 'SENT', 1, 'N', "
                        + "CURDATE(), 'N', 'dr-test', NOW(), '0', 0)",
                logId, subId, receiver);
        return logId;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
