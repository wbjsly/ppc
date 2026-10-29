package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.UarService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 季度权限复核全链（add-access-review-console spec：SOP-5.6-D）：
 * 分层生成/快照固化/同周期幂等/判定理由 422/越权 403/回收绕守卫/
 * 报告统计/双签归档/单签不归档/逾期升级幂等。
 */
@SpringBootTest
class UarDbTest {

    @Autowired
    private UarService service;
    @Autowired
    private JdbcTemplate jdbc;

    private String reviewer;
    private String target;

    @BeforeEach
    void setUp() {
        purge();
        reviewer = seedUser("uard-mgr");
        target = seedUser("uard-tgt");
        // target 持普通角色，汇报线 → reviewer
        grantRole(target, "ROLE_PLANNER");
        String empMgr = seedEmployee("UARDMGR", reviewer, null);
        seedEmployee("UARDTGT", target, empMgr);
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        // UAR 表仅测试/冒烟写入且各自段尾清理，直清全表（CYCLE_TAG 唯一约束防跨测残留）
        jdbc.execute("DELETE FROM erp_admin_uar_report");
        jdbc.execute("DELETE FROM erp_admin_uar_item");
        jdbc.execute("DELETE FROM erp_admin_uar_task");
        jdbc.execute("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'uard-%'");
        jdbc.execute("DELETE FROM erp_admin_grant_ledger WHERE TARGET_USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'uard-%') t)");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'uard-%'");
        jdbc.execute("DELETE FROM hr_employee WHERE EMP_NO LIKE 'UARD%'");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'uard-%') t); SET @audit_bypass=0");
        SecurityContextHolder.clearContext();
    }

    private String seedUser(String name) {
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','uard-test','0',0)",
                id, name, "{noop}", "UAR测试");
        return id;
    }

    private String seedEmployee(String empNo, String userId, String reportTo) {
        String id = "uard-emp-" + empNo;
        jdbc.update("INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,EMP_STATUS,HIRE_DATE,"
                        + "USER_ID,REPORT_TO_EMP_ID,ATTEND_SCHEME,CREATE_BY,DEL_FLAG,VER_NO) "
                        + "VALUES (?,?,?,?,'在职',CURDATE(),?,?,'STD','uard-test','0',0)",
                id, empNo, "UAR" + empNo, "UARDID-" + empNo, userId, reportTo);
        return id;
    }

    private void grantRole(String userId, String roleCode) {
        jdbc.update("INSERT INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) "
                        + "SELECT ?,?,ID FROM erp_admin_role WHERE ROLE_CODE=?",
                "uard-ur-" + UUID.randomUUID().toString().substring(0, 8), userId, roleCode);
    }

    private void login(String userId, String... roles) {
        var authorities = java.util.stream.Stream.of(roles)
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, authorities));
    }

    private String firstItemId(String taskId) {
        return jdbc.queryForObject(
                "SELECT ID FROM erp_admin_uar_item WHERE TASK_ID=? AND TARGET_USER_ID=? "
                        + "ORDER BY ROLE_CODE LIMIT 1",
                String.class, taskId, target);
    }

    // ---------- 2.x 生成与快照 ----------

    @Test
    void layeredGenerationAndReviewerFallback() {
        // 特权账号（无汇报线 → ADMIN 兜底）+ 敏感账号
        String priv = seedUser("uard-priv");
        grantRole(priv, "ROLE_ADMIN");
        String sens = seedUser("uard-sens");
        grantRole(sens, "ROLE_COST_ACCOUNTANT");

        login(reviewer, "ROLE_ADMIN");
        Map<String, Object> out = service.generate();
        String taskId = String.valueOf(out.get("taskId"));
        // target（普通+汇报线）→ 按人复核人；priv（特权）→ ADMIN 兜底
        String reviewerOfTarget = jdbc.queryForObject(
                "SELECT REVIEWER_USER_ID FROM erp_admin_uar_item WHERE TASK_ID=? AND TARGET_USER_ID=?",
                String.class, taskId, target);
        assertEquals(reviewer, reviewerOfTarget, "REPORT_TO 主管按人指派");
        String reviewerOfPriv = jdbc.queryForObject(
                "SELECT REVIEWER_ROLE FROM erp_admin_uar_item WHERE TASK_ID=? AND TARGET_USER_ID=?",
                String.class, taskId, priv);
        assertEquals("ROLE_ADMIN", reviewerOfPriv, "无汇报线回落 ADMIN");
        // 敏感账号 100% 进人工
        Integer sensItems = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_uar_item WHERE TASK_ID=? AND TARGET_USER_ID=?",
                Integer.class, taskId, sens);
        assertTrue(sensItems >= 1, "敏感账号 100% 复核");
        // 同周期重复生成 422
        ServiceException e = assertThrows(ServiceException.class, () -> service.generate());
        assertEquals(422, e.getCode());
    }

    @Test
    void snapshotFrozenAndThreeAnomalies() {
        // 目标账号：无任何写操作记录 → UNUSED；持有基线外角色（PLANNER 不在空基线）→ BEYOND_BASELINE
        login(reviewer, "ROLE_ADMIN");
        Map<String, Object> out = service.generate();
        String taskId = String.valueOf(out.get("taskId"));
        String snap = jdbc.queryForObject(
                "SELECT SNAPSHOT_JSON FROM erp_admin_uar_item WHERE TASK_ID=? AND TARGET_USER_ID=? LIMIT 1",
                String.class, taskId, target);
        assertTrue(snap.contains("UNUSED"), "90 天零写操作标记 UNUSED");
        assertTrue(snap.contains("BEYOND_BASELINE"), "空基线 + PLANNER 标记超基线");
        // 快照固化：生成后加角色不进本次范围
        grantRole(target, "ROLE_SALES");
        Integer items = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_uar_item WHERE TASK_ID=? AND TARGET_USER_ID=?",
                Integer.class, taskId, target);
        assertEquals(1, items, "快照固化，新角色不进本次复核");
    }

    // ---------- 3.x 判定与回收 ----------

    @Test
    void judgeReasonBlockAndUnauthorized() {
        login(reviewer, "ROLE_ADMIN");
        String taskId = String.valueOf(service.generate().get("taskId"));
        String itemId = firstItemId(taskId);
        // 越权 403：非复核人
        String stranger = seedUser("uard-str");
        login(stranger, "ROLE_ADMIN");
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.judge(itemId, "KEEP", "足够长度的业务保留理由说明"));
        assertEquals(403, e1.getCode());
        // 保留未使用/超基线角色理由过短 422（L1）
        login(reviewer, "ROLE_ADMIN");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.judge(itemId, "KEEP", "留"));
        assertEquals(422, e2.getCode());
        // 理由充分 → KEEP
        Map<String, Object> ok = service.judge(itemId, "KEEP", "生产补货计划查看确需该角色权限");
        assertEquals("KEEP", ok.get("judgment"));
        // 重复判定 422
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> service.judge(itemId, "RECLAIM", "重复判定尝试理由"));
        assertEquals(422, e3.getCode());
    }

    @Test
    void reclaimBypassesBaselineGuard() {
        // 基线含 PLANNER；target 再持 SALES（基线外）→ 进人工清单；收回 PLANNER 项须绕守卫
        grantRole(target, "ROLE_SALES");
        String posId = "uard-pos-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO erp_admin_position_baseline (ID,POS_ID,ROLE_CODES,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,'[\"ROLE_PLANNER\"]','uard-test','0',0)",
                "uard-bl-" + UUID.randomUUID().toString().substring(0, 8), posId);
        jdbc.update("UPDATE hr_employee SET POS_ID=? WHERE USER_ID=?", posId, target);

        login(reviewer, "ROLE_ADMIN");
        String taskId = String.valueOf(service.generate().get("taskId"));
        String itemId = firstItemId(taskId);
        // 制造台账 ACTIVE 行（走台账回收路径）
        jdbc.update("INSERT INTO erp_admin_grant_ledger (ID,APPLY_ID,TARGET_USER_ID,ROLE_CODE,"
                        + "SOURCE,GRANT_AT,EXPIRE_AT,STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) "
                        + "VALUES (?,?,?,?,'NORMAL',NOW(),DATE_ADD(NOW(), INTERVAL 1 MONTH),'ACTIVE',"
                        + "'uard-test',NOW(),'0',0)",
                "uard-led-" + UUID.randomUUID().toString().substring(0, 8),
                "uard-apply-" + UUID.randomUUID().toString().substring(0, 8), target, "ROLE_PLANNER");
        service.judge(itemId, "RECLAIM", null);
        Map<String, Object> rc = service.executeReclaim(itemId);
        assertEquals(true, rc.get("executed"));
        // 投影被删除（基线守卫绕过）
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                        + "WHERE ur.USER_ID=? AND r.ROLE_CODE='ROLE_PLANNER'", Integer.class, target);
        assertEquals(0, bound, "UAR 权威收回绕过基线守卫");
        // 幂等
        Map<String, Object> rc2 = service.executeReclaim(itemId);
        assertEquals(false, rc2.get("executed"));
    }

    @Test
    void reclaimWithoutLedgerDeletesProjection() {
        login(reviewer, "ROLE_ADMIN");
        String taskId = String.valueOf(service.generate().get("taskId"));
        String itemId = firstItemId(taskId);
        service.judge(itemId, "RECLAIM", null);
        Map<String, Object> rc = service.executeReclaim(itemId);
        assertEquals(true, rc.get("executed"));
        String exec = jdbc.queryForObject("SELECT EXEC_STATUS FROM erp_admin_uar_item WHERE ID=?",
                String.class, itemId);
        assertEquals("EXECUTED", exec);
    }

    // ---------- 4.x 报告与升级 ----------

    @Test
    void reportStatsAndDualSignArchive() {
        // 任务覆盖全库活跃账号：全量判定 RECLAIM → 覆盖 100%、收回 100%
        grantRole(reviewer, "ROLE_ADMIN");
        login(reviewer, "ROLE_ADMIN");
        String taskId = String.valueOf(service.generate().get("taskId"));
        for (Map<String, Object> it : service.itemList(taskId, false)) {
            service.judge(String.valueOf(it.get("id")), "RECLAIM", null);
        }
        Map<String, Object> sum = service.summarize(taskId);
        assertEquals(100, ((Number) sum.get("coverageRate")).intValue());
        assertEquals(100, ((Number) sum.get("reclaimRate")).intValue());
        // 单签不归档：先内审会签（未确认）→ 422
        ServiceException e1 = assertThrows(ServiceException.class, () -> service.signReport(taskId));
        assertEquals(422, e1.getCode());
        // ADMIN 确认 → 内审会签 → 归档
        service.confirmReport(taskId);
        service.signReport(taskId);
        String archived = jdbc.queryForObject("SELECT ARCHIVED FROM erp_admin_uar_report WHERE TASK_ID=?",
                String.class, taskId);
        assertEquals("1", archived);
        String taskStatus = jdbc.queryForObject("SELECT STATUS FROM erp_admin_uar_task WHERE ID=?",
                String.class, taskId);
        assertEquals("ARCHIVED", taskStatus);
        // 重复签署 422
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.signReport(taskId));
        assertEquals(422, e2.getCode());
    }

    @Test
    void overdueEscalationIdempotent() {
        // reviewer 持 ADMIN（兜底项按角色授权）；target 汇报线指向 reviewer
        grantRole(reviewer, "ROLE_ADMIN");
        login(reviewer, "ROLE_ADMIN");
        String taskId = String.valueOf(service.generate().get("taskId"));
        // 回拨截止至 1 天后（≤ now+2d）且完成率 <100%
        jdbc.update("UPDATE erp_admin_uar_task SET DUE_AT=DATE_ADD(NOW(), INTERVAL 1 DAY) WHERE ID=?",
                taskId);
        Map<String, Object> sw1 = service.sweepOverdue();
        assertTrue(((Number) sw1.get("escalated")).intValue() >= 1, "首次升级");
        // 幂等：二次扫描不重复升级（12h 内）
        Map<String, Object> sw2 = service.sweepOverdue();
        assertEquals(0, ((Number) sw2.get("escalated")).intValue());
        // 通知落库（DEPT_HEAD 或 AUDITOR 收到）
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_ID=? AND TITLE LIKE '%逾期%'",
                Integer.class, taskId);
        assertTrue(notices >= 1, "升级通知");
        // 完成任务不升级：判定全部 item 后 sweep 跳过
        List<Map<String, Object>> items = service.itemList(taskId, false);
        for (Map<String, Object> it : items) {
            service.judge(String.valueOf(it.get("id")), "KEEP", "逾期场景保留确需业务理由说明");
        }
        jdbc.update("UPDATE erp_admin_uar_task SET ESCALATED_AT=NULL, REMIND_AT=NULL WHERE ID=?", taskId);
        Map<String, Object> sw3 = service.sweepOverdue();
        assertEquals(0, ((Number) sw3.get("escalated")).intValue(), "完成任务不升级");
    }
}
