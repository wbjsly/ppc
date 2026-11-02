package com.erp.i18n;

import com.erp.common.ServiceException;
import com.erp.service.i18n.I18nKeyService;
import com.erp.service.i18n.I18nPackService;
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
 * 17.1 语言包版本发布库测（add-i18n-l10n，BR-5.7-01/02/03、BR-5.7-07、C-0-05）。
 */
@SpringBootTest
class I18nPackDbTest {

    @Autowired
    private I18nPackService packService;
    @Autowired
    private I18nKeyService keyService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String T = "SMK-I18N-";

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void precheckJaHasGaps() {
        // 种子 ja-JP 有 12 个缺口 → 总体 <99%? 208/220=94.5% <99 → 不通过
        Map<String, Object> pc = packService.precheck("ja-JP");
        assertEquals("ja-JP", pc.get("locale"));
        assertTrue(((Number) pc.get("totalKeys")).longValue() > 200, "源语键 >200");
        assertFalse((Boolean) pc.get("pass"), "ja 覆盖率 94.5% < 99% 应阻断");
        assertNotNull(pc.get("blockReason"));
        assertTrue(((List<?>) pc.get("gaps")).size() >= 10, "缺口清单列出");
    }

    @Test
    void precheckEnPasses() {
        // en-US 全译 220/220=100% ≥99 → 通过
        Map<String, Object> pc = packService.precheck("en-US");
        assertTrue((Boolean) pc.get("pass"), "en 覆盖率 100% 应通过");
        assertEquals(0, ((List<?>) pc.get("gaps")).size(), "en 无缺口");
    }

    @Test
    void buildImmutableVersion() {
        Map<String, Object> r = packService.build("ja-JP", "9.9.9", T);
        assertNotNull(r.get("id"));
        // 重复构建同版本 → 422（不可变）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> packService.build("ja-JP", "9.9.9", T));
        assertEquals(422, ex.getCode());
        // 非法版本号
        assertThrows(ServiceException.class, () -> packService.build("ja-JP", "bad-version", T));
        // 源语不可建版本
        assertThrows(ServiceException.class, () -> packService.build("zh-CN", "1.0.0", T));
    }

    @Test
    void approveBlockedWhenCoverageLow() {
        Map<String, Object> r = packService.build("ja-JP", "9.9.8", T);
        String id = String.valueOf(r.get("id"));
        // ja 覆盖率不足 → 审批 422（BR-5.7-01/02）
        ServiceException ex = assertThrows(ServiceException.class, () -> packService.approve(id));
        assertEquals(422, ex.getCode());
    }

    @Test
    void approvePassesWhenCoverage100() {
        Map<String, Object> r = packService.build("en-US", "9.9.7", T);
        String id = String.valueOf(r.get("id"));
        Map<String, Object> a = packService.approve(id);
        assertEquals("APPROVED", a.get("status"));
    }

    @Test
    void canaryRequiresFallbackPolicy() {
        Map<String, Object> r = packService.build("en-US", "9.9.6", T);
        String id = String.valueOf(r.get("id"));
        packService.approve(id);
        // 无回退策略 → 灰度 422（SOP-5.7-B 步3 前置）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> packService.startCanary(id, 10));
        assertEquals(422, ex.getCode());
        // 配置回退策略后可灰度
        jdbc.update("UPDATE erp_i18n_pack SET FALLBACK_POLICY_JSON=? WHERE ID=?",
                "{\"locale\":\"zh-CN\",\"visualMark\":true,\"track\":true}", id);
        Map<String, Object> c = packService.startCanary(id, 10);
        assertEquals("CANARY", c.get("status"));
    }

    @Test
    void canaryAutoRollbackWhenErrRateHigh() {
        Map<String, Object> r = packService.build("en-US", "9.9.5", T);
        String id = String.valueOf(r.get("id"));
        packService.approve(id);
        jdbc.update("UPDATE erp_i18n_pack SET FALLBACK_POLICY_JSON=? WHERE ID=?",
                "{\"locale\":\"zh-CN\"}", id);
        packService.startCanary(id, 10);
        // 注入高异常率：err=5, req=100 → 5% > 0.1%
        jdbc.update("UPDATE erp_i18n_pack SET RENDER_ERR_CNT=5, RENDER_REQ_CNT=100 WHERE ID=?", id);
        Map<String, Object> chk = packService.checkCanary(id);
        assertTrue((Boolean) chk.get("rolledBack"), "异常率 5% > 0.1% 自动回退（BR-5.7-07）");
        assertEquals("DRAFT", chk.get("status"));
    }

    @Test
    void archivedPackImmutable() {
        Map<String, Object> r = packService.build("en-US", "9.9.4", T);
        String id = String.valueOf(r.get("id"));
        packService.archive(id);
        Map<String, Object> a = packService.archive(id); // 幂等
        assertEquals("ARCHIVED", a.get("status"));
        // 归档后状态不可回 DRAFT（锁）
        jdbc.queryForObject("SELECT STATUS FROM erp_i18n_pack WHERE ID=?", String.class, id);
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_i18n_pack WHERE ID=?", String.class, id);
        assertEquals("ARCHIVED", st, "归档锁定（C-0-05）");
    }

    @Test
    void placeholderMismatchRejectedOnImport() {
        // 源语键含 {0}，译文占位符不一致 → 422 拒绝（BR-5.7-03）
        keyService.save(Map.of("keyName", T + "ph", "locale", "zh-CN", "module", "test",
                "priority", "P1", "sourceText", "你好 {0}，欢迎 {name}"));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                keyService.importCsv("ja-JP", T + "ph,こんにちは {0}"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("占位符"), "提示占位符不一致");
        // 占位符一致 → 导入成功
        int n = keyService.importCsv("ja-JP", T + "ph,こんにちは {0}、{name} さん");
        assertEquals(1, n);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_i18n_pack WHERE REMARK=?", T);
        jdbc.update("DELETE FROM erp_i18n_key WHERE KEY_NAME LIKE ?", T + "%");
    }
}
