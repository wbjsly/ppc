package com.erp.i18n;

import com.erp.common.ServiceException;
import com.erp.service.i18n.ComplianceFieldService;
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
 * 17.5 合规字段包引擎库测（add-i18n-l10n，BR-5.7-13 / C-5.7-09 / 17.5.3 无冗余）。
 */
@SpringBootTest
class ComplianceFieldDbTest {

    @Autowired
    private ComplianceFieldService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String PK = "SMK_CF";

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void seedPacksPresent() {
        // 迁移种子三包
        List<Map<String, Object>> all = service.list(null, null);
        assertTrue(all.size() >= 3, "种子三包（JP/CN/EU）");
        List<Map<String, Object>> jp = service.list("JP_INVOICE", "JP");
        assertFalse(jp.isEmpty());
        assertEquals("^T\\d{13}$", jp.get(0).get("formatRegex"), "日本登录番号正则 T+13");
    }

    @Test
    void loadByRegPlaceFilters() {
        // JP 注册地 → 有字段；US → 空清单（无冗余，17.5.3）
        assertFalse(service.loadByRegPlace("JP").isEmpty(), "JP 有包字段");
        assertTrue(service.loadByRegPlace("US").isEmpty(), "US 无包无冗余字段");
        assertTrue(service.loadByRegPlace("").isEmpty(), "空注册地空清单");
    }

    @Test
    void validateBlocksInvalidRegNo() {
        // 日本登录番号格式校验（C-5.7-09）
        Map<String, Object> bad = service.validate("JP",
                Map.of("invoiceRegNo", "1234567890123")); // 缺 T 前缀
        assertFalse((Boolean) bad.get("pass"), "缺 T 前缀应阻断");
        assertFalse(((List<?>) bad.get("errors")).isEmpty());

        Map<String, Object> ok = service.validate("JP",
                Map.of("invoiceRegNo", "T1234567890123"));
        assertTrue((Boolean) ok.get("pass"), "T+13 位通过");
    }

    @Test
    void validateRequiredMissing() {
        // 必填字段为空 → 阻断
        Map<String, Object> r = service.validate("JP", Map.of());
        assertFalse((Boolean) r.get("pass"), "必填缺失阻断");
    }

    @Test
    void saveInvalidRegexRejected() {
        ServiceException ex = assertThrows(com.erp.common.ServiceException.class, () ->
                service.save(Map.of("packCode", PK, "regPlace", "JP", "fieldName", "badFmt",
                        "formatRegex", "([invalid")));
        assertEquals(422, ex.getCode());
    }

    @Test
    void saveUpsertField() {
        service.save(Map.of("packCode", PK, "regPlace", "JP", "fieldName", "smkField",
                "columnName", "TAXPAYER_NO", "formatRegex", "^SMK\\d+$",
                "required", "1", "errorHint", "SMK 格式", "sortNo", 9));
        // 重复保存 → 更新不重复
        service.save(Map.of("packCode", PK, "regPlace", "JP", "fieldName", "smkField",
                "columnName", "TAXPAYER_NO", "formatRegex", "^SMK\\d{4}$",
                "required", "1", "errorHint", "SMK 格式2", "sortNo", 9));
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_compliance_field WHERE PACK_CODE=? AND FIELD_NAME=?",
                Integer.class, PK, "smkField");
        assertEquals(1, cnt, "UK 幂等 upsert");
        String rx = jdbc.queryForObject(
                "SELECT FORMAT_REGEX FROM erp_i18n_compliance_field WHERE PACK_CODE=? AND FIELD_NAME=?",
                String.class, PK, "smkField");
        assertEquals("^SMK\\d{4}$", rx, "更新生效");
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_i18n_compliance_field WHERE PACK_CODE=?", PK);
    }
}
