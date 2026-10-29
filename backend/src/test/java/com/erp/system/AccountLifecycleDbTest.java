package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.system.SysUser;
import com.erp.security.JwtTokenProvider;
import com.erp.service.AuthService;
import com.erp.service.scm.CpfrClockService;
import com.erp.service.system.AccountLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 账号生命周期 DbTest（add-account-lifecycle-console，spec account-lifecycle）：
 * 强制改密流转（复杂度 422 / 成功清除标记）、离职 24h 兜底强停幂等（BR-5.6-09）、
 * 停用 90 天自动归档、归档不可恢复（C-0-05）与二次确认。
 * 造数 USERNAME 前缀 acc-，物理 DELETE 清理；固定 Clock 走 CpfrClockService 替身。
 */
@SpringBootTest
class AccountLifecycleDbTest {

    @Autowired
    private AccountLifecycleService lifecycleService;
    @Autowired
    private AuthService authService;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private SysUserDao userDao;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CpfrClockService clockService;

    private String userId;

    @BeforeEach
    void setUp() {
        purge();
        clockService.setClock(Clock.systemDefaultZone());
        SysUser u = new SysUser();
        u.setId(uuid());
        u.setUsername("acc-" + UUID.randomUUID().toString().substring(0, 8));
        u.setPassword(passwordEncoder.encode("Old@1234"));
        u.setNickName("账号生命周期测试");
        u.setStatus(SysUser.ST_ACTIVE);
        u.setMustChangePwd("1");
        userDao.insert(u);
        userId = u.getId();
    }

    @AfterEach
    void tearDown() {
        purge();
        clockService.setClock(Clock.systemDefaultZone());
    }

    // ---------- 强制改密 ----------

    @Test
    void weakPasswordRejectedAndStrongPasswordClearsFlag() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                authService.changePassword(bearer(userId), "Old@1234", "Abcdefg1"));
        assertEquals(422, e.getCode(), "缺特殊字符拒绝");
        assertEquals("1", current().getMustChangePwd(), "标记保持");
        Map<String, Object> out = authService.changePassword(bearer(userId), "Old@1234", "New@Pass12");
        assertEquals(false, out.get("mustChangePwd"));
        assertEquals("0", current().getMustChangePwd(), "改密成功清除标记");
    }

    @Test
    void oldPasswordMismatchRejected() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                authService.changePassword(bearer(userId), "Wrong@123", "New@Pass12"));
        assertEquals(422, e.getCode());
        assertEquals("1", current().getMustChangePwd());
    }

    // ---------- 24h 强停 + 90 天归档 ----------

    @Test
    void sweepDisablesExitOver24hAndArchivesAfter90Days() {
        seedEmployeeWithExit(userId, LocalDate.of(2026, 10, 25)); // 固定时钟前 3 天
        clockService.setClock(Clock.fixed(Instant.parse("2026-10-28T04:00:00Z"), ZoneId.of("Asia/Shanghai")));
        Map<String, Object> out = lifecycleService.sweep();
        assertEquals(1, out.get("disabled"), "离职超 24h 强停 1 个");
        assertEquals("0", current().getStatus(), "账号已停用");
        Integer snap = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? AND FIELD_NAME='ACCOUNT_DISABLED'",
                Integer.class, userId);
        assertEquals(1, snap, "强停快照审计 1 条");
        Integer alert = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? AND FIELD_NAME='SWEEP_ALERT'",
                Integer.class, userId);
        assertEquals(1, alert, "BR-5.6-09 告警审计 1 条");
        // 幂等
        Map<String, Object> again = lifecycleService.sweep();
        assertEquals(0, again.get("disabled"), "重复 sweep 幂等");
        // 停用未满 90 天不归档
        assertEquals("0", current().getStatus());
        // 回拨 UPDATE_DATE 91 天 → 归档
        jdbc.update("UPDATE erp_admin_user SET UPDATE_DATE = DATE_SUB('2026-10-28 12:00:00', INTERVAL 91 DAY) WHERE ID=?", userId); // 与注入固定时钟同源（NOW() 混用会在每日 12:00 后越界误红）
        Map<String, Object> out3 = lifecycleService.sweep();
        assertEquals(1, out3.get("archived"), "停用 91 天自动归档");
        SysUser archived = current();
        assertEquals(SysUser.ST_ARCHIVED, archived.getStatus());
        assertNotNull(archived.getArchivedAt(), "ARCHIVED_AT 落库");
    }

    @Test
    void sweepSkipsExitWithin24h() {
        seedEmployeeWithExit(userId, LocalDate.of(2026, 10, 27)); // 前 1 天 → 未满 24h
        clockService.setClock(Clock.fixed(Instant.parse("2026-10-28T04:00:00Z"), ZoneId.of("Asia/Shanghai")));
        Map<String, Object> out = lifecycleService.sweep();
        assertEquals(0, out.get("disabled"), "未满 24h 不干预");
        assertEquals("1", current().getStatus());
    }

    // ---------- 归档语义 ----------

    @Test
    void archiveRequiresConfirmAndIsIrreversible() {
        ServiceException e1 = assertThrows(ServiceException.class, () ->
                lifecycleService.archive(userId, current().getUsername(), "tester"));
        assertEquals(422, e1.getCode(), "未停用不可归档");
        lifecycleService.disable(userId, "tester");
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                lifecycleService.archive(userId, "wrong-name", "tester"));
        assertEquals(422, e2.getCode(), "错确认用户名拒绝");
        assertEquals("0", current().getStatus());
        lifecycleService.archive(userId, current().getUsername(), "tester");
        assertEquals(SysUser.ST_ARCHIVED, current().getStatus());
        ServiceException e3 = assertThrows(ServiceException.class, () ->
                lifecycleService.disable(userId, "tester"));
        assertEquals(422, e3.getCode(), "归档账号不可操作");
        ServiceException e4 = assertThrows(ServiceException.class, () ->
                lifecycleService.archive(userId, current().getUsername(), "tester"));
        assertEquals(422, e4.getCode(), "归档不可恢复（C-0-05）");
    }

    // ---------- 辅助 ----------

    private String bearer(String uid) {
        return "Bearer " + jwtTokenProvider.generateToken(uid, Map.of(), 60_000L);
    }

    private void seedEmployeeWithExit(String uid, LocalDate quitDate) {
        String empId = "acc-emp-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, USER_ID, "
                        + "ATTEND_SCHEME, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES (?,?,?,?,'在职','2026-01-01',?,'STD','acc-emp','0',0)",
                empId, "ACC" + UUID.randomUUID().toString().substring(0, 6), "离职测试员工",
                "11010119900101" + UUID.randomUUID().toString().substring(0, 4), uid);
        jdbc.update("INSERT INTO hr_exit (ID, EXIT_NO, EMP_ID, EMP_NAME, EMP_NO, QUIT_DATE, SOURCE, "
                        + "NOTICE_OK, STATUS, ACCOUNT_DONE, DEL_FLAG, VER_NO) "
                        + "VALUES (?,?,?,?,?,?,'离职','1','已完成','1','0',0)",
                "acc-exit-" + UUID.randomUUID().toString().substring(0, 8),
                "ACC-EXIT-" + UUID.randomUUID().toString().substring(0, 6),
                empId, "离职测试员工", "ACC001", quitDate);
    }

    private SysUser current() {
        return userDao.selectById(userId);
    }

    private void purge() {
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID LIKE 'acc-%'; SET @audit_bypass=0");
        jdbc.execute("DELETE FROM hr_exit WHERE EXIT_NO LIKE 'ACC-EXIT-%'");
        jdbc.execute("DELETE FROM hr_employee WHERE EMP_NO LIKE 'ACC%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'acc-%'");
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
