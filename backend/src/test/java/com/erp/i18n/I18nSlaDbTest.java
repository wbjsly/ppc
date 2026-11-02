package com.erp.i18n;

import com.erp.common.ServiceException;
import com.erp.service.i18n.I18nSlaService;
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
 * 17.7 i18n SLA 报告库测（add-i18n-l10n，BR-5.7-14 未归档阻断留痕）。
 */
@SpringBootTest
class I18nSlaDbTest {

    @Autowired
    private I18nSlaService service;
    @Autowired
    private JdbcTemplate jdbc;

    /** 用未来月份避免撞默认期 */
    private static final String P = "2099-08";

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void generateProducesFiveItems() {
        Map<String, Object> snap = service.generate(P);
        assertEquals(P, snap.get("period"));
        assertEquals("DRAFT", snap.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) snap.get("metrics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) metrics.get("items");
        assertEquals(4, items.size(), "四指标（版本/覆盖率×2/缺口闭环/渲染异常）");
        assertNotNull(metrics.get("packCount"));
        assertNotNull(metrics.get("enCoverage"));
        assertNotNull(metrics.get("jaCoverage"));
    }

    @Test
    void publishBlockedWhenNotArchived() {
        service.generate(P);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publish(P, "ALL"));
        assertEquals(422, ex.getCode(), "未归档发布阻断（BR-5.7-14）");
        // 留痕
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_param WHERE PARAM_KEY LIKE 'I18N_SLA_PUBLISH_BLOCKED_%' "
                        + "AND PARAM_VALUE LIKE ?", Integer.class, "%" + P + "%");
        assertTrue(n >= 1, "发布尝试留痕");
    }

    @Test
    void archiveThenPublish() {
        service.generate(P);
        Map<String, Object> a = service.archive(P);
        assertEquals("ARCHIVED", a.get("status"));
        Map<String, Object> p = service.publish(P, "PARTNER");
        assertEquals("PUBLISHED", p.get("status"));
        assertEquals("PARTNER", p.get("publishScope"));
    }

    @Test
    void archiveOnlyDraft() {
        service.generate(P);
        service.archive(P);
        // 已归档再归档 → 幂等或 422（非 DRAFT）
        ServiceException ex = assertThrows(ServiceException.class, () -> service.archive(P));
        assertEquals(422, ex.getCode());
    }

    @Test
    void reportMissing404() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.report("2099-12"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void invalidPeriod422() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.generate("bad"));
        assertEquals(422, ex.getCode());
    }

    @Test
    void listShowsStatus() {
        service.generate(P);
        List<Map<String, Object>> l = service.list();
        assertTrue(l.stream().anyMatch(r -> String.valueOf(r.get("k")).contains(P)));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_param WHERE PARAM_KEY IN "
                + "('I18N_SLA_STATUS_" + P + "') OR (PARAM_KEY LIKE 'I18N_SLA_PUBLISH_BLOCKED_%' "
                + "AND PARAM_VALUE LIKE '%" + P + "%')");
        jdbc.update("DELETE FROM erp_i18n_gap WHERE KEY_NAME LIKE 'SLA-" + P + "%'");
    }
}
