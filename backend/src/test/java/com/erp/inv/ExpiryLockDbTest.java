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

/**
 * 效期锁定每日扫描 DB 侧语义（真实 MySQL，spec outbound-strategy 效期两级标记，任务 2.2）：
 * 过线批次置 EXPIRY_LOCK_FLAG='1'、未过线保持 '0'（双向）、效期修正后下次扫描回位、
 * 生产日期缺失跳过（不误锁）。
 */
@SpringBootTest
class ExpiryLockDbTest {

    private static final String ITEM = "IT-EXPIRY-LOCK-TEST";

    @Autowired
    private BatchRecommendService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
    }

    private void insertBatch(String batch, LocalDate production, LocalDate expiry, String lock) {
        jdbc.update("INSERT INTO erp_inv_batch "
                        + "(ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, EXPIRY_DATE, "
                        + "EXPIRY_LOCK_FLAG, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, '1', 'junit', '0', 0)",
                "junit-" + batch, batch, ITEM, "效期锁定测试物料",
                production == null ? null : java.sql.Date.valueOf(production),
                expiry == null ? null : java.sql.Date.valueOf(expiry),
                lock);
    }

    private String flagOf(String batch) {
        return jdbc.queryForObject(
                "SELECT EXPIRY_LOCK_FLAG FROM erp_inv_batch WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                String.class, ITEM, batch);
    }

    /** 过线置 1、未过线保持 0（BR-4.4-20，ratio 默认 0.5） */
    @Test
    void scanTogglesOverAndUnderThreshold() {
        // 总 100 天，已过 60 天 → 剩 40 < 50 → 锁定
        insertBatch("B-LOCK", LocalDate.now().minusDays(60), LocalDate.now().plusDays(40), "0");
        // 总 1000 天，剩 900+ → 不锁
        insertBatch("B-OPEN", LocalDate.now().minusDays(100), LocalDate.now().plusDays(900), "0");

        int changed = service.scanExpiryLock();

        assertEquals("1", flagOf("B-LOCK"));
        assertEquals("0", flagOf("B-OPEN"));
        // 至少 B-LOCK 一条发生翻转
        org.junit.jupiter.api.Assertions.assertTrue(changed >= 1);
    }

    /** 效期修正后下次扫描回位（双向自愈） */
    @Test
    void scanReleasesWhenExpiryExtended() {
        insertBatch("B-FIX", LocalDate.now().minusDays(60), LocalDate.now().plusDays(40), "1");
        assertEquals("1", flagOf("B-FIX"));

        // 业务修正效期（如盘点补录纠错）→ 拉远到安全区
        jdbc.update("UPDATE erp_inv_batch SET EXPIRY_DATE = ? WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                java.sql.Date.valueOf(LocalDate.now().plusDays(900)), ITEM, "B-FIX");

        service.scanExpiryLock();
        assertEquals("0", flagOf("B-FIX"));
    }

    /** 生产日期缺失无法计算总天数 → 跳过不误锁（保持原标记） */
    @Test
    void scanSkipsBatchWithoutProductionDate() {
        insertBatch("B-NOPROD", null, LocalDate.now().plusDays(10), "0");
        service.scanExpiryLock();
        assertEquals("0", flagOf("B-NOPROD"));
    }

    /** 幂等：连续两次扫描结果一致 */
    @Test
    void scanIsIdempotent() {
        insertBatch("B-IDEM", LocalDate.now().minusDays(60), LocalDate.now().plusDays(40), "0");
        service.scanExpiryLock();
        int second = service.scanExpiryLock();
        assertEquals("1", flagOf("B-IDEM"));
        // 第二次无变化（首条已置位，其余为别的批次）
        assertEquals(0, second);
    }
}
