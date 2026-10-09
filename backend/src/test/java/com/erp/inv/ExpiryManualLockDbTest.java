package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.service.inv.ExpiryLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人工锁定 DB 侧（spec expiry-management 需求③，任务 2.4）：
 * 即刻生效留痕、原因空 422、质量角色 403、历史可查、重复锁定 422。
 */
@SpringBootTest
class ExpiryManualLockDbTest {

    private static final String ITEM = "IT-MANUAL-LOCK-TEST";

    @Autowired
    private ExpiryLockService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        // 未到线批次（剩余远高于阈值）
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                        + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, LOCK_SOURCE, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('junit-ml-1', 'B-ML-1', ?, '人工锁定测试物料', ?, ?, '0', 'AUTO', "
                        + "'1', 'junit', '0', 0)",
                ITEM, LocalDate.now().minusDays(10).toString(), LocalDate.now().plusDays(900).toString());
        login("wh-ml", "ROLE_WAREHOUSE");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        SecurityContextHolder.clearContext();
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    /** 需求③：即刻生效免审批 + 变更历史留痕 */
    @Test
    void manualLockEffectiveImmediatelyWithHistory() {
        Map<String, Object> out = service.manualLock("B-ML-1", ITEM, "外观异常主动隔离");

        assertEquals(InvBatchConst.LOCKED, jdbc.queryForObject(
                "SELECT EXPIRY_LOCK_FLAG FROM erp_inv_batch WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                String.class, ITEM, "B-ML-1"));
        assertEquals("MANUAL", jdbc.queryForObject(
                "SELECT LOCK_SOURCE FROM erp_inv_batch WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                String.class, ITEM, "B-ML-1"));
        assertEquals("wh-ml", out.get("operator"));

        List<Map<String, Object>> hist = service.history("B-ML-1", ITEM, 10);
        assertEquals(1, hist.size(), "历史留痕一条");
        assertEquals("MANUAL", hist.get(0).get("source"));
        assertEquals("外观异常主动隔离", hist.get(0).get("reason"));
        assertEquals("0", hist.get(0).get("fromFlag"));
        assertEquals("1", hist.get(0).get("toFlag"));
    }

    /** 原因空 422 且状态不变 */
    @Test
    void blankReasonRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.manualLock("B-ML-1", ITEM, " "));
        assertEquals(422, e.getCode());
        assertEquals("0", jdbc.queryForObject(
                "SELECT EXPIRY_LOCK_FLAG FROM erp_inv_batch WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                String.class, ITEM, "B-ML-1"));
    }

    /** 质量角色 403（接口层角色不放松） */
    @Test
    void qualityRoleForbidden() {
        login("q-ml", "ROLE_QUALITY_ENG");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.manualLock("B-ML-1", ITEM, "测试"));
        assertEquals(403, e.getCode());
    }

    /** 重复锁定 422 */
    @Test
    void duplicateLockRejected() {
        service.manualLock("B-ML-1", ITEM, "第一次锁定");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.manualLock("B-ML-1", ITEM, "第二次锁定"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("已处于锁定状态"));
    }

    /** 台账展示锁定批次与来源 */
    @Test
    void ledgerShowsLockedBatchWithSource() {
        service.manualLock("B-ML-1", ITEM, "入台账测试");
        Map<String, Object> page = service.ledger(ITEM, null, "MANUAL", 1, 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> records = (List<Map<String, Object>>) page.get("records");
        assertEquals(1, records.size());
        assertEquals("B-ML-1", records.get(0).get("batchNo"));
        assertEquals("MANUAL", records.get(0).get("lockSource"));
        assertTrue(((Number) page.get("total")).longValue() >= 1);
    }
}

/** 本地断言常量（避免引实体静态字符串的包噪音） */
class InvBatchConst {
    static final String LOCKED = "1";
}
