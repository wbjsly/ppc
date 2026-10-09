package com.erp.inv;

import com.erp.service.inv.BatchRecommendService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫描三类口径 DB 侧（spec expiry-management 需求③⑤，design D2，任务 2.2）：
 * 豁免强制清位、MANUAL 只评估不清除、AUTO 双向自愈不变、flag 变化写变更历史、幂等零噪音。
 */
@SpringBootTest
class ExpiryLockScanDbTest {

    private static final String ITEM = "IT-EXPIRY-SCAN-TEST";

    @Autowired
    private BatchRecommendService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
    }

    private void insertBatch(String batch, LocalDate production, LocalDate expiry,
                             String lock, String lockSource, LocalDate exemptUntil) {
        jdbc.update("INSERT INTO erp_inv_batch "
                        + "(ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, EXPIRY_DATE, "
                        + "EXPIRY_LOCK_FLAG, LOCK_SOURCE, EVAL_EXEMPT_UNTIL, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '1', 'junit', '0', 0)",
                "junit-" + batch, batch, ITEM, "扫描口径测试物料",
                production == null ? null : java.sql.Date.valueOf(production),
                expiry == null ? null : java.sql.Date.valueOf(expiry),
                lock, lockSource,
                exemptUntil == null ? null : java.sql.Date.valueOf(exemptUntil));
    }

    private String flagOf(String batch) {
        return jdbc.queryForObject(
                "SELECT EXPIRY_LOCK_FLAG FROM erp_inv_batch WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                String.class, ITEM, batch);
    }

    private int logCount(String batch) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                Integer.class, ITEM, batch);
    }

    /** 需求⑤：豁免未过期强制清位（已锁的放行位被扫描确认），过期回归 AUTO 重算 */
    @Test
    void exemptionForcesUnlockAndExpires() {
        // 已锁 + 未来豁免 → 扫描清位
        insertBatch("B-EXEMPT", LocalDate.now().minusDays(60), LocalDate.now().plusDays(40),
                "1", "AUTO", LocalDate.now().plusDays(10));
        // 已锁 + 过期豁免 → 回归 AUTO 重算（仍到线）保持 1，且豁免列被清
        insertBatch("B-EXPIRED", LocalDate.now().minusDays(60), LocalDate.now().plusDays(40),
                "1", "AUTO", LocalDate.now().minusDays(1));

        service.scanExpiryLock();

        assertEquals("0", flagOf("B-EXEMPT"), "有效豁免强制不锁");
        assertEquals("1", flagOf("B-EXPIRED"), "过期豁免回归到线锁定");
        Object cleared = jdbc.queryForObject(
                "SELECT EVAL_EXEMPT_UNTIL FROM erp_inv_batch WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                java.sql.Date.class, ITEM, "B-EXPIRED");
        assertEquals(null, cleared, "过期豁免清除回归 AUTO 语义");
        assertEquals("SCAN", jdbc.queryForObject(
                "SELECT SOURCE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                String.class, ITEM, "B-EXEMPT"), "flag 变化写变更历史");
    }

    /** 需求③：MANUAL 锁扫描只评估不清除（未到线也保持锁） */
    @Test
    void manualLockNeverClearedByScan() {
        // 人工锁但剩余天数远高于阈值（未到线）
        insertBatch("B-MANUAL", LocalDate.now().minusDays(10), LocalDate.now().plusDays(900),
                "1", "MANUAL", null);

        service.scanExpiryLock();

        assertEquals("1", flagOf("B-MANUAL"), "人工锁不被扫描回位清除");
        assertEquals(0, logCount("B-MANUAL"), "无变化零噪音（不写历史）");
    }

    /** AUTO 双向自愈不变（既有行为回归）+ 幂等 */
    @Test
    void autoBidirectionalAndIdempotent() {
        insertBatch("B-AUTO-LOCK", LocalDate.now().minusDays(60), LocalDate.now().plusDays(40),
                "0", "AUTO", null);
        insertBatch("B-AUTO-OPEN", LocalDate.now().minusDays(10), LocalDate.now().plusDays(900),
                "1", "AUTO", null);

        int first = service.scanExpiryLock();
        assertEquals("1", flagOf("B-AUTO-LOCK"));
        assertEquals("0", flagOf("B-AUTO-OPEN"));
        assertTrue(first >= 2, "首轮至少两次翻转");
        int logAfterFirst = logCount("B-AUTO-LOCK");
        assertEquals(1, logAfterFirst, "翻转写一条历史");

        int second = service.scanExpiryLock();
        assertEquals(0, second, "二次扫描幂等零变更");
        assertEquals(1, logCount("B-AUTO-LOCK"), "幂等不追加历史");
    }
}
