package com.erp.system;

import com.erp.service.GrantLedgerService;
import com.erp.service.GrantSweepService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到期回收与续期提醒（add-permission-approval-flow spec：到期回收与续期）：
 * 到期回收+快照、幂等、7 日提醒恰一次、续期新单不扰原台账、基线内角色保投影。
 */
@SpringBootTest
class ReclaimDbTest {

    @Autowired
    private GrantSweepService sweepService;
    @Autowired
    private GrantLedgerService ledgerService;
    @Autowired
    private com.erp.service.PermRequestService permRequestService;
    @Autowired
    private JdbcTemplate jdbc;

    private String user;
    private String plannerRole;

    @BeforeEach
    void setUp() {
        purge();
        user = "rcs-u" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','rcs-test','0',0)",
                user, user, "{noop}", "回收测试用户");
        plannerRole = jdbc.queryForObject(
                "SELECT ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_PLANNER'", String.class);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.execute("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'rcs-u%'");
        jdbc.execute("DELETE FROM erp_admin_grant_ledger WHERE APPLY_ID LIKE 'rcs-%' "
                + "OR TARGET_USER_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'rcs-u%') t)");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE FIELD_NAME IN "
                + "('PERMISSION_RECLAIMED','PERMISSION_GRANTED') AND EMP_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'rcs-u%') t); SET @audit_bypass=0");
        jdbc.execute("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('GrantLedger','PermRequest') "
                + "AND (TARGET_USER IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'rcs-u%') t) OR BIZ_ID LIKE 'rcs-%')");
        jdbc.execute("DELETE FROM erp_admin_perm_request WHERE ID LIKE 'rcs-req%' "
                + "OR TARGET_USER_ID IN (SELECT ID FROM (SELECT ID FROM erp_admin_user "
                + "WHERE USERNAME LIKE 'rcs-u%') t)");
        jdbc.execute("DELETE FROM erp_admin_position_baseline WHERE POS_ID LIKE 'rcs-pos%'");
        jdbc.execute("DELETE FROM hr_employee WHERE EMP_NO LIKE 'RCS%' OR ID LIKE 'rcs-emp%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'rcs-u%'");
    }

    private String seedRequest(String status) {
        String id = "rcs-req-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_admin_perm_request (ID,KIND,TARGET_USER_ID,APPLICANT_USER_ID,"
                        + "ROLES_JSON,RISK_LEVEL,DATA_SCOPE,REASON,VALID_MONTHS,ACTIVATION_MODE,"
                        + "STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) "
                        + "VALUES (?,'NORMAL',?,?,'[\"ROLE_PLANNER\"]','NORMAL','华东/OU-01',"
                        + "'回收测试申请理由十个字',12,'IMMEDIATE',?,'rcs-test',NOW(),'0',0)",
                id, user, user, status);
        return id;
    }

    private String seedLedger(String applyId, LocalDateTime expireAt) {
        String id = "rcs-gl-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_admin_grant_ledger (ID,APPLY_ID,TARGET_USER_ID,ROLE_CODE,"
                        + "SOURCE,GRANT_AT,EXPIRE_AT,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) "
                        + "VALUES (?,?,?,'ROLE_PLANNER','NORMAL',?,?, 'ACTIVE','rcs-test',NOW(),'0',0)",
                id, applyId, user, LocalDateTime.now().minusMonths(13), expireAt);
        // 生效投影
        jdbc.update("INSERT INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) VALUES (?,?,?)",
                "rcs-ur-" + UUID.randomUUID().toString().substring(0, 8), user, plannerRole);
        return id;
    }

    @Test
    void dueLedgerReclaimedWithSnapshot() {
        String applyId = seedRequest("ACTIVE");
        String gl = seedLedger(applyId, LocalDateTime.now().minusMinutes(1));

        Map<String, Object> out = sweepService.sweep(LocalDateTime.now());
        assertEquals(1, out.get("reclaimed"), "到期行须回收：" + out);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT STATUS, SNAPSHOT_JSON, RECLAIM_AT FROM erp_admin_grant_ledger WHERE ID=?", gl);
        assertEquals("RECLAIMED", row.get("STATUS"));
        assertNotNull(row.get("SNAPSHOT_JSON"), "回收前权限快照必填");
        assertTrue(String.valueOf(row.get("SNAPSHOT_JSON")).contains("ROLE_PLANNER"));
        assertNotNull(row.get("RECLAIM_AT"));

        // 投影移除（该用户无岗位基线）
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role WHERE USER_ID=?", Integer.class, user);
        assertEquals(0, bound, "到期回收须移除角色投影");
        // 申请单回写 EXPIRED + 审计
        String reqStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?", String.class, applyId);
        assertEquals("EXPIRED", reqStatus);
        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? AND FIELD_NAME='PERMISSION_RECLAIMED'",
                Integer.class, user);
        assertEquals(1, audit);
    }

    @Test
    void reclaimIsIdempotent() {
        String applyId = seedRequest("ACTIVE");
        String gl = seedLedger(applyId, LocalDateTime.now().minusMinutes(1));
        sweepService.sweep(LocalDateTime.now());

        Map<String, Object> again = ledgerService.reclaim(gl, "rcs-test");
        assertEquals(false, again.get("reclaimed"), "重复回收幂等返回现态");
        assertEquals("RECLAIMED", again.get("status"));
        Map<String, Object> sweep2 = sweepService.sweep(LocalDateTime.now());
        assertEquals(0, sweep2.get("reclaimed"), "二次 sweep 无新增回收");
    }

    @Test
    void sevenDayReminderExactlyOnce() {
        String applyId = seedRequest("ACTIVE");
        String gl = seedLedger(applyId, LocalDateTime.now().plusDays(3));

        Map<String, Object> out1 = sweepService.sweep(LocalDateTime.now());
        assertEquals(1, out1.get("reminded"), "7 日内须提醒一次：" + out1);
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_ID=? AND TITLE='权限到期提醒'",
                Integer.class, gl);
        assertEquals(1, notices);
        String remindAt = jdbc.queryForObject(
                "SELECT CAST(REMIND_AT AS CHAR) FROM erp_admin_grant_ledger WHERE ID=?",
                String.class, gl);
        assertNotNull(remindAt, "REMIND_AT 须落库（幂等标记）");

        Map<String, Object> out2 = sweepService.sweep(LocalDateTime.now());
        assertEquals(0, out2.get("reminded"), "重复 sweep 不重复提醒");
        // 未到期不回收
        assertEquals(0, out2.get("reclaimed"));
    }

    @Test
    void renewalCreatesNewRequestWithoutTouchingOldLedger() {
        String applyId = seedRequest("ACTIVE");
        String gl = seedLedger(applyId, LocalDateTime.now().plusDays(3));
        sweepService.sweep(LocalDateTime.now()); // 触发提醒

        // 续期 = 重新提交完整申请单（SOP step6：不得一键续期），原台账行不动
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "rcs-admin", null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        Map<String, Object> renewal = permRequestService.submitNormal(user, java.util.List.of("ROLE_PLANNER"),
                "华东/OU-01", "续期重新申请的业务理由", null, 12, "IMMEDIATE", null);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        assertEquals("PENDING_APPROVAL", renewal.get("status"), "续期生成新申请单进入完整审批链");
        String oldStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_grant_ledger WHERE ID=?", String.class, gl);
        assertEquals("ACTIVE", oldStatus, "续期审批期间原授予保持有效");

        // 原单到期照常回收，直至新单生效（回收不扰动新单）
        jdbc.update("UPDATE erp_admin_grant_ledger SET EXPIRE_AT=? WHERE ID=?",
                LocalDateTime.now().minusMinutes(1), gl);
        Map<String, Object> out = sweepService.sweep(LocalDateTime.now());
        assertEquals(1, out.get("reclaimed"));
        String newStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_perm_request WHERE ID=?",
                String.class, String.valueOf(renewal.get("id")));
        assertEquals("PENDING_APPROVAL", newStatus, "回收不扰动续期新单");
    }

    @Test
    void baselineRoleKeepsProjectionOnExpiry() {
        // 有岗位基线含 ROLE_PLANNER 的用户：到期后台账回收但投影存续（design D6 守卫）
        String posId = "rcs-pos-" + UUID.randomUUID().toString().substring(0, 8);
        String empId = "rcs-emp-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,EMP_STATUS,HIRE_DATE,"
                        + "USER_ID,POS_ID,ATTEND_SCHEME,CREATE_BY,DEL_FLAG,VER_NO) "
                        + "VALUES (?,?,?,'RCSID-','在职',CURDATE(),?,?,'STD','rcs-test','0',0)",
                empId, "RCS" + UUID.randomUUID().toString().substring(0, 6), "回收基线员工",
                user, posId);
        jdbc.update("INSERT INTO erp_admin_position_baseline (ID,POS_ID,ROLE_CODES,CREATE_BY,"
                        + "CREATE_DATE,DEL_FLAG,VER_NO) VALUES (?,?,?,'rcs-test',NOW(),'0',0)",
                "rcs-bl-" + UUID.randomUUID().toString().substring(0, 8), posId, "[\"ROLE_PLANNER\"]");

        String applyId = seedRequest("ACTIVE");
        String gl = seedLedger(applyId, LocalDateTime.now().minusMinutes(1));
        sweepService.sweep(LocalDateTime.now());

        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_admin_grant_ledger WHERE ID=?", String.class, gl);
        assertEquals("RECLAIMED", status);
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role WHERE USER_ID=?", Integer.class, user);
        assertEquals(1, bound, "基线内角色到期后投影存续（基线授权仍在）");
    }
}
