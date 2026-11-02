package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotTerminalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13 域终端注册与签审双因子库测（add-iot-mobile，C-4.11-18）。
 * 场景：未注册拒绝 / 连续 3 次失败锁定 / 管理员解锁留痕。
 */
@SpringBootTest
class IotTerminalDbTest {

    @Autowired
    private IotTerminalService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String T = "smk-iot-term-";
    private static final String CODE = T + "pda01";

    @BeforeEach
    void setUp() {
        // 以 admin 身份运行（签审密码比对 admin 口令）
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("user-admin", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_terminal WHERE DEVICE_CODE LIKE ?", T + "%");
        jdbc.update("DELETE FROM erp_iot_alarm_event WHERE DEVICE_NO LIKE ?", T + "%");
    }

    private Map<String, String> signBody(String code, String password) {
        Map<String, String> b = new HashMap<>();
        b.put("deviceCode", code);
        b.put("password", password);
        return b;
    }

    @Test
    void unregisteredTerminalRejected() {
        // 未注册终端携带有效会话 → 403（C-4.11-18 L1 硬阻断）+ 安全事件落库
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.verifySign(signBody(T + "ghost", "any")));
        assertEquals(403, ex.getCode());
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_alarm_event WHERE DEVICE_NO=?", Integer.class, T + "ghost");
        assertEquals(1, events, "未注册尝试应写安全告警事件");
    }

    @Test
    void threeConsecutiveFailuresLockTerminal() {
        service.register(signBody(CODE, null));
        // 连续 3 次错误密码 → 锁定 + 告警
        for (int i = 1; i <= 3; i++) {
            final String badPwd = "wrong-pwd-" + i;
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> service.verifySign(signBody(CODE, badPwd)));
            assertEquals(403, ex.getCode());
        }
        Map<String, Object> d = service.detail(CODE);
        assertEquals("1", d.get("lockedFlag"), "3 次失败应锁定");
        assertNotNull(d.get("lockReason"));
        assertNotNull(d.get("lockAt"));
        // 锁定后再验（即使密码正确）也拒绝
        ServiceException locked = assertThrows(ServiceException.class,
                () -> service.verifySign(signBody(CODE, "any")));
        assertEquals(403, locked.getCode());
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_alarm_event WHERE DEVICE_NO=? AND ALERT_TYPE='SECURITY'",
                Integer.class, CODE);
        assertTrue(events >= 1, "锁定应写安全告警");
    }

    @Test
    void adminUnlockLeavesTrace() {
        service.register(signBody(CODE, null));
        // 先锁
        for (int i = 0; i < 3; i++) {
            assertThrows(ServiceException.class, () -> service.verifySign(signBody(CODE, "bad")));
        }
        Map<String, Object> d = service.unlock(CODE, "现场核实为本人操作");
        assertEquals("0", d.get("lockedFlag"), "解锁后锁定标记清除");
        assertEquals(0, d.get("failCnt"), "失败计数清零");
        assertNotNull(d.get("unlockBy"), "解锁人留痕");
        assertNotNull(d.get("unlockAt"), "解锁时间留痕");
        assertTrue(String.valueOf(d.get("remark")).contains("现场核实"),
                "解锁原因写入 remark 留痕");
    }
}
