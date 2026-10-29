package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.ActivationSweepService;
import com.erp.service.GrantLedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授予台账与生效（add-permission-approval-flow spec：生效策略与变更窗口校验 / 授予台账）：
 * 生效落台账+投影+审计+通知、特权窗口内外、指定时点 sweep、失败整体回滚、例外来源。
 */
@SpringBootTest
class GrantActivationDbTest {

    @Autowired
    private GrantLedgerService ledgerService;
    @Autowired
    private ActivationSweepService activationSweep;
    @Autowired
    private JdbcTemplate jdbc;

    private String user;

    @BeforeEach
    void setUp() {
        purge();
        user = "ga-u" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','ga-test','0',0)",
                user, user, "{noop}", "台账测试用户");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.execute("DELETE FROM erp_admin_grant_ledger WHERE APPLY_ID LIKE 'ga-%' "
                + "OR TARGET_USER_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'ga-u%') t)");
        jdbc.execute("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'ga-u%'");
        jdbc.execute("DELETE FROM erp_admin_perm_request WHERE ID LIKE 'ga-%'");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'ga-u%') t); SET @audit_bypass=0");
        jdbc.execute("DELETE FROM erp_sys_notice WHERE BIZ_ID LIKE 'ga-%' "
                + "OR TARGET_USER IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'ga-u%') t)");
        jdbc.execute("DELETE FROM erp_change_window WHERE ID LIKE 'ga-win%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'ga-u%'");
    }

    private String seedRequest(String status, String activationMode, LocalDateTime activateAt) {
        String id = "ga-req-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_admin_perm_request (ID,KIND,TARGET_USER_ID,APPLICANT_USER_ID,"
                        + "ROLES_JSON,RISK_LEVEL,DATA_SCOPE,REASON,VALID_MONTHS,ACTIVATION_MODE,"
                        + "ACTIVATE_AT,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) "
                        + "VALUES (?,'NORMAL',?,?,'[\"ROLE_PLANNER\"]','NORMAL','华东/OU-01',"
                        + "'台账测试申请理由十个字',12,?,?,?,?,NOW(),'0',0)",
                id, user, user, activationMode, activateAt, status, "ga-test");
        return id;
    }

    @Test
    void activateWritesLedgerProjectionAuditNotice() {
        String applyId = seedRequest("PENDING_APPROVAL", "IMMEDIATE", null);
        LocalDateTime expire = LocalDateTime.now().plusMonths(12);

        Map<String, Object> out = ledgerService.activate(applyId, user,
                List.of("ROLE_PLANNER"), "NORMAL", expire, false, "ga-test");

        @SuppressWarnings("unchecked")
        List<String> grants = (List<String>) out.get("grantIds");
        assertEquals(1, grants.size());

        // 台账按 APPLY_ID 反查（C-5.6-01 锚点）
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT ROLE_CODE, SOURCE, STATUS, EXPIRE_AT FROM erp_admin_grant_ledger "
                        + "WHERE APPLY_ID=?", applyId);
        assertEquals("ROLE_PLANNER", ledger.get("ROLE_CODE"));
        assertEquals("NORMAL", ledger.get("SOURCE"));
        assertEquals("ACTIVE", ledger.get("STATUS"));

        // 生效投影
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                        + "WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_PLANNER'", Integer.class, user);
        assertEquals(1, bound, "角色投影须写入");

        // 申请单回填
        Map<String, Object> req = jdbc.queryForMap(
                "SELECT STATUS, GRANT_ID FROM erp_admin_perm_request WHERE ID=?", applyId);
        assertEquals("ACTIVE", req.get("STATUS"));

        // 审计 + 站内通知
        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? AND FIELD_NAME='PERMISSION_GRANTED'",
                Integer.class, user);
        assertEquals(1, audit);
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_ID=? AND TITLE='权限已生效'",
                Integer.class, applyId);
        assertEquals(1, notices);
    }

    @Test
    void exceptionSourceRecordedInLedger() {
        String applyId = seedRequest("PENDING_APPROVAL", "IMMEDIATE", null);
        ledgerService.activate(applyId, user, List.of("ROLE_PLANNER"), "EXCEPTION",
                LocalDateTime.now().plusMonths(12), false, "ga-test");
        String source = jdbc.queryForObject(
                "SELECT SOURCE FROM erp_admin_grant_ledger WHERE APPLY_ID=?", String.class, applyId);
        assertEquals("EXCEPTION", source, "例外授予同入台账且来源可辨");
    }

    @Test
    void privilegedActivationRejectedOutsideWindowThenPasses() {
        String applyId = seedRequest("APPROVED_PENDING", "IMMEDIATE", null);
        // 无窗口（ga-win 未插入）→ 特权生效 422，零副作用
        ServiceException e = assertThrows(ServiceException.class,
                () -> ledgerService.activate(applyId, user, List.of("ROLE_PLANNER"),
                        "NORMAL", LocalDateTime.now().plusMonths(12), true, "ga-test"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("变更窗口"), e.getMessage());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_grant_ledger WHERE APPLY_ID=?", Integer.class, applyId);
        assertEquals(0, rows, "窗口外拒绝须零台账");
        String reqStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?", String.class, applyId);
        assertEquals("APPROVED_PENDING", reqStatus, "拒绝后申请单保持待生效（标待窗口）");

        // 插入覆盖当前时刻的窗口 → 放行
        int day = LocalDateTime.now().getDayOfWeek().getValue();
        jdbc.update("INSERT INTO erp_change_window (ID,NAME,DAY_OF_WEEK,START_TIME,END_TIME,STATUS,"
                        + "CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES ('ga-win-1','GA全天窗口',?,'00:00:00',"
                        + "'23:59:59','1','ga-test',NOW(),'0',0)", day);
        ledgerService.activate(applyId, user, List.of("ROLE_PLANNER"), "NORMAL",
                LocalDateTime.now().plusMonths(12), true, "ga-test");
        rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_grant_ledger WHERE APPLY_ID=?", Integer.class, applyId);
        assertEquals(1, rows, "窗口内特权生效放行");
    }

    @Test
    void scheduledActivationSweep() {
        String applyId = seedRequest("APPROVED_PENDING", "SCHEDULED", LocalDateTime.now().plusHours(1));
        // 未到点 → sweep 不生效
        Map<String, Object> r1 = activationSweep.sweepDue(LocalDateTime.now());
        assertEquals(0, r1.get("activated"));
        String st1 = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?", String.class, applyId);
        assertEquals("APPROVED_PENDING", st1);

        // 到点 → 生效
        jdbc.update("UPDATE erp_admin_perm_request SET ACTIVATE_AT = ? WHERE ID=?",
                LocalDateTime.now().minusMinutes(1), applyId);
        Map<String, Object> r2 = activationSweep.sweepDue(LocalDateTime.now());
        assertEquals(1, r2.get("activated"), "到点申请单须生效：" + r2);
        String st2 = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?", String.class, applyId);
        assertEquals("ACTIVE", st2);
        Integer ledger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_grant_ledger WHERE APPLY_ID=?", Integer.class, applyId);
        assertEquals(1, ledger);
    }

    @Test
    void failureRollsBackLedgerAndProjection() {
        String applyId = seedRequest("PENDING_APPROVAL", "IMMEDIATE", null);
        // 第二个角色未知 → 第一笔台账已写入后抛出 → 整体回滚
        ServiceException e = assertThrows(ServiceException.class,
                () -> ledgerService.activate(applyId, user,
                        List.of("ROLE_PLANNER", "ROLE_NOPE_XYZ"), "NORMAL",
                        LocalDateTime.now().plusMonths(12), false, "ga-test"));
        assertEquals(422, e.getCode());
        Integer ledger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_grant_ledger WHERE APPLY_ID=?", Integer.class, applyId);
        assertEquals(0, ledger, "失败须整体回滚台账");
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role WHERE USER_ID=?", Integer.class, user);
        assertEquals(0, bound, "失败须整体回滚投影");
    }
}
