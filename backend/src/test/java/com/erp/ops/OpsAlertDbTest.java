package com.erp.ops;

import com.erp.dao.ops.OpsAlertDao;
import com.erp.dao.ops.OpsEscalationLogDao;
import com.erp.dao.ops.OpsOncallDao;
import com.erp.entity.ops.OpsAlert;
import com.erp.entity.ops.OpsEscalationLog;
import com.erp.entity.ops.OpsOncall;
import com.erp.service.ops.EscalationService;
import com.erp.service.ops.OpsAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 18.2/18.3 告警与升级库测（add-ops-sla，spec ops-sla）：
 * 风暴聚合（BR-5.8-08）/ P1 豁免（BR-5.8-09）/ 认领与升级（BR-5.8-02/03）。
 */
@SpringBootTest
class OpsAlertDbTest {

    @Autowired
    private OpsAlertService alertService;
    @Autowired
    private EscalationService escalationService;
    @Autowired
    private OpsAlertDao alertDao;
    @Autowired
    private OpsOncallDao oncallDao;
    @Autowired
    private OpsEscalationLogDao escLogDao;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String SRC = "smk-alert-src";

    @BeforeEach
    void setUp() {
        purge();
        // 值班表：今天 L1/L2/L3
        saveOncall("L1", "op-l1");
        saveOncall("L2", "op-l2");
        saveOncall("L3", "op-l3");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void raiseCreatesOpenAlertAndNotice() {
        String id = alertService.raise("P2", SRC, "测试告警", "详情", BigDecimal.valueOf(10), BigDecimal.valueOf(5), null);
        assertNotNull(id);
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT STATUS, LEVEL, SUPPRESSED FROM erp_ops_alert WHERE ID=?", id);
        assertEquals("OPEN", a.get("STATUS"));
        assertEquals("P2", a.get("LEVEL"));
        // 站内信推送
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='OpsAlert' AND BIZ_ID=?",
                Integer.class, id);
        assertTrue(n >= 1, "告警推送站内信");
    }

    @Test
    void stormAggregatesAndSuppresses() {
        // 灌入 200 条同来源（风暴阈值 200）——用批量 SQL 提速
        for (int i = 0; i < 200; i++) {
            jdbc.update("INSERT INTO erp_ops_alert (ID, ALERT_NO, LEVEL, SOURCE, TITLE, STATUS, "
                            + "SUPPRESSED, ESCALATION_LEVEL, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (UUID(), ?, 'P3', ?, '风暴前置', 'OPEN', '0', 0, 't', NOW(), '0', 0)",
                    "N" + i, SRC);
        }
        // 第 201 条成为聚合根因（不抑制，推送根因事件）
        String rootId = alertService.raise("P3", SRC, "风暴根因", null, null, null, null);
        Map<String, Object> root = jdbc.queryForMap(
                "SELECT SUPPRESSED, ROOT_CAUSE_ID, STATUS FROM erp_ops_alert WHERE ID=?", rootId);
        assertEquals("0", root.get("SUPPRESSED"), "首条风暴条目成为根因不被抑制");
        // 第 202 条挂靠根因并被抑制
        String id = alertService.raise("P3", SRC, "风暴条目", null, null, null, null);
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT SUPPRESSED, ROOT_CAUSE_ID, STATUS FROM erp_ops_alert WHERE ID=?", id);
        assertEquals("1", a.get("SUPPRESSED"), "后续条目被抑制聚合");
        assertEquals("SUPPRESSED", a.get("STATUS"));
        assertEquals(rootId, a.get("ROOT_CAUSE_ID"), "挂靠根因");
    }

    @Test
    void p1ExemptFromStorm() {
        for (int i = 0; i < 200; i++) {
            jdbc.update("INSERT INTO erp_ops_alert (ID, ALERT_NO, LEVEL, SOURCE, TITLE, STATUS, "
                            + "SUPPRESSED, ESCALATION_LEVEL, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (UUID(), ?, 'P3', ?, '风暴前置', 'OPEN', '0', 0, 't', NOW(), '0', 0)",
                    "P" + i, SRC);
        }
        // 风暴期内 P1 豁免（BR-5.8-09）
        String id = alertService.raise("P1", SRC, "P1 豁免条目", null, null, null, null);
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT SUPPRESSED, STATUS FROM erp_ops_alert WHERE ID=?", id);
        assertEquals("0", a.get("SUPPRESSED"), "P1 不被抑制");
        assertEquals("OPEN", a.get("STATUS"));
    }

    @Test
    void claimAndResolve() {
        String id = alertService.raise("P3", SRC, "认领测试", null, null, null, null);
        alertService.claim(id, "op-l1");
        assertEquals("CLAIMED", statusOf(id));
        alertService.resolve(id, "已修复");
        assertEquals("RESOLVED", statusOf(id));
    }

    @Test
    void claimOnlyOpen() {
        String id = alertService.raise("P3", SRC, "重复认领", null, null, null, null);
        alertService.claim(id, "op-l1");
        assertThrows(com.erp.common.ServiceException.class, () -> alertService.claim(id, "op-l2"));
    }

    @Test
    void p1TimeoutEscalatesToL1OnCall() {
        // 造 20 分钟前的 OPEN P1 告警（超 P1_RESPONSE_MIN=15）
        String id = insertAgedAlert("P1", 20);
        int handled = escalationService.scanAndEscalate();
        assertTrue(handled >= 1);
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT ESCALATION_LEVEL, STATUS, HANDLER FROM erp_ops_alert WHERE ID=?", id);
        assertEquals(1, ((Number) a.get("ESCALATION_LEVEL")).intValue(), "升级至 L1");
        assertEquals("ESCALATED", a.get("STATUS"));
        assertEquals("op-l1", a.get("HANDLER"), "改派 L1 值班人");
        // 升级日志
        Integer logCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_ops_escalation_log WHERE ALERT_ID=?", Integer.class, id);
        assertTrue(logCnt >= 1, "升级日志留痕");
    }

    @Test
    void levelTimeoutEscalatesL1ToL2() {
        // 已升级 L1 的 40 分钟前告警（超 ESCALATION_RESPONSE_MIN=30）→ 升 L2
        String id = insertAgedAlert("P2", 40);
        // 已升 L1 后 40 分钟无处置：保持 OPEN 让扫描器按层级继续升（实现扫描 OPEN）
        jdbc.update("UPDATE erp_ops_alert SET ESCALATION_LEVEL=1, "
                + "CREATE_DATE=DATE_SUB(NOW(), INTERVAL 40 MINUTE) WHERE ID=?", id);
        escalationService.scanAndEscalate();
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT ESCALATION_LEVEL, HANDLER FROM erp_ops_alert WHERE ID=?", id);
        assertEquals(2, ((Number) a.get("ESCALATION_LEVEL")).intValue(), "升级至 L2");
        assertEquals("op-l2", a.get("HANDLER"));
    }

    @Test
    void alreadyAtL3NotEscalatedAgain() {
        String id = insertAgedAlert("P1", 60);
        jdbc.update("UPDATE erp_ops_alert SET ESCALATION_LEVEL=3, STATUS='ESCALATED' WHERE ID=?", id);
        escalationService.scanAndEscalate();
        assertEquals(3, ((Number) jdbc.queryForObject(
                "SELECT ESCALATION_LEVEL FROM erp_ops_alert WHERE ID=?", Integer.class, id)).intValue(),
                "L3 顶不再升级");
    }

    @Test
    void oncallLookup() {
        assertEquals("op-l1", escalationService.oncallOf(LocalDate.now(), "L1"));
    }

    @Test
    void suppressedNotEscalated() {
        String id = insertAgedAlert("P3", 60);
        jdbc.update("UPDATE erp_ops_alert SET SUPPRESSED='1', ROOT_CAUSE_ID=? WHERE ID=?", id, id);
        escalationService.scanAndEscalate();
        assertEquals(0, ((Number) jdbc.queryForObject(
                "SELECT ESCALATION_LEVEL FROM erp_ops_alert WHERE ID=?", Integer.class, id)).intValue(),
                "被抑制告警不参与升级");
    }

    @Test
    void escalationLogsQueryable() {
        String id = insertAgedAlert("P1", 20);
        escalationService.scanAndEscalate();
        List<Map<String, Object>> logs = escalationService.escalationLogs(id, 10);
        assertFalse(logs.isEmpty());
        assertEquals("L1", logs.get(0).get("toLevel"));
    }

    // ---------------- 工具 ----------------

    private void saveOncall(String level, String user) {
        // UK (DUTY_DATE, ON_LEVEL)：先清同日同层级残留（含冒烟 smk81 排班）
        jdbc.update("DELETE FROM erp_ops_oncall WHERE DUTY_DATE=? AND ON_LEVEL=?",
                LocalDate.now(), level);
        OpsOncall o = new OpsOncall();
        o.setDutyDate(LocalDate.now());
        o.setOnLevel(level);
        o.setUserName(user);
        o.setCreateBy("test");
        oncallDao.insert(o);
    }

    private String insertAgedAlert(String level, int minutesAgo) {
        OpsAlert a = new OpsAlert();
        a.setAlertNo("OA-" + System.nanoTime());
        a.setLevel(level);
        a.setSource(SRC);
        a.setTitle("老化告警");
        a.setStatus(OpsAlert.ST_OPEN);
        a.setEscalationLevel(0);
        a.setSuppressed("0");
        a.setCreateBy("test");
        a.setCreateDate(LocalDateTime.now().minusMinutes(minutesAgo));
        alertDao.insert(a);
        return a.getId();
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_ops_alert WHERE ID=?", String.class, id);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_ops_escalation_log WHERE ALERT_ID IN "
                + "(SELECT ID FROM erp_ops_alert WHERE SOURCE=?)", SRC);
        jdbc.update("DELETE FROM erp_ops_alert WHERE SOURCE=?", SRC);
        jdbc.update("DELETE FROM erp_ops_oncall WHERE USER_NAME LIKE 'op-l%'");
    }
}
