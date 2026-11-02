package com.erp.i18n;

import com.erp.common.ServiceException;
import com.erp.service.i18n.I18nGapService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 17.2 缺口台账库测（add-i18n-l10n，BR-5.7-05 批量回传 UPSERT 累加）。
 */
@SpringBootTest
class I18nGapDbTest {

    @Autowired
    private I18nGapService gapService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String KEY = "SMK-GAP-key1";

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void batchUpsertAccumulates() {
        // 首次回传
        gapService.reportBatch(List.of(Map.of("keyName", KEY, "moduleName", "test",
                "priority", "P2", "hitCount", 10)));
        // 二次回传累加，FIRST_HIT_AT 不变
        gapService.reportBatch(List.of(Map.of("keyName", KEY, "hitCount", 5)));
        Map<String, Object> g = jdbc.queryForMap(
                "SELECT HIT_COUNT, FIRST_HIT_AT, PRIORITY FROM erp_i18n_gap WHERE KEY_NAME=?", KEY);
        assertEquals(15, ((Number) g.get("HIT_COUNT")).intValue(), "HIT_COUNT 累加 10+5");
        assertEquals("P2", g.get("PRIORITY"));
    }

    @Test
    void highFreqAutoEscalatesToP1() {
        // 单批 600 次 > 500 → 自动升 P1（SOP-5.7-A 步7）
        gapService.reportBatch(List.of(Map.of("keyName", KEY, "hitCount", 600)));
        String pri = jdbc.queryForObject(
                "SELECT PRIORITY FROM erp_i18n_gap WHERE KEY_NAME=?", String.class, KEY);
        assertEquals("P1", pri, "周命中 >500 自动升 P1");
    }

    @Test
    void emptyBatchRejected() {
        ServiceException ex = assertThrows(com.erp.common.ServiceException.class,
                () -> gapService.reportBatch(List.of()));
        assertEquals(422, ex.getCode());
    }

    @Test
    void coverageReflectsSeedGaps() {
        // ja-JP 种子有 12 缺口 → coverage <100% 且 gaps 非空
        Map<String, Object> cov = gapService.coverage("ja-JP");
        assertTrue(((Number) cov.get("totalKeys")).longValue() > 200);
        assertTrue(((Number) cov.get("coverage")).doubleValue() < 100, "ja 有缺口 <100%");
        assertTrue(((List<?>) cov.get("gaps")).size() >= 10);
        // en-US 全译 → 100%
        Map<String, Object> en = gapService.coverage("en-US");
        assertEquals(100.0, ((Number) en.get("coverage")).doubleValue(), "en 100%");
    }

    @Test
    void closeGap() {
        gapService.reportBatch(List.of(Map.of("keyName", KEY, "hitCount", 1)));
        String id = jdbc.queryForObject(
                "SELECT ID FROM erp_i18n_gap WHERE KEY_NAME=?", String.class, KEY);
        gapService.close(id);
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_i18n_gap WHERE KEY_NAME=?", String.class, KEY);
        assertEquals("CLOSED", st);
        assertNotNull(jdbc.queryForObject("SELECT CLOSED_AT FROM erp_i18n_gap WHERE KEY_NAME=?",
                java.time.LocalDateTime.class, KEY));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_i18n_gap WHERE KEY_NAME LIKE 'SMK-GAP%'");
    }
}
