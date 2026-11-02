package com.erp.service.impl.i18n;

import com.erp.common.ServiceException;
import com.erp.service.i18n.LocaleConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域格式配置实现（add-i18n-l10n，C-5.7-02/08）。
 * 配置存 erp_sys_param（LOCALE_FMT_<locale> JSON），三语内置默认模板。
 */
@Slf4j
@Service
public class LocaleConfigServiceImpl implements LocaleConfigService {

    private static final String PARAM_PREFIX = "LOCALE_FMT_";

    /** 三语内置默认格式模板 */
    private static final Map<String, Map<String, String>> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("zh-CN", def("yyyy-MM-dd", "#,##0.00", "¥", "2", "1[3-9]\\d{9}", "\\d{6}"));
        DEFAULTS.put("en-US", def("MM/dd/yyyy", "#,##0.00", "$", "2", "\\+?1[-.\\s]?\\d{10}", "\\d{5}"));
        DEFAULTS.put("ja-JP", def("yyyy/MM/dd", "#,##0", "¥", "0", "\\+81[-.\\s]?\\d{8,10}", "\\d{3}-\\d{4}"));
    }

    private static Map<String, String> def(String date, String number, String currency,
                                           String decimals, String phone, String postal) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("datePattern", date);
        m.put("numberPattern", number);
        m.put("currencySymbol", currency);
        m.put("decimalPlaces", decimals);
        m.put("phoneRegex", phone);
        m.put("postalRegex", postal);
        return m;
    }

    private final JdbcTemplate jdbc;

    public LocaleConfigServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String locale : DEFAULTS.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("locale", locale);
            row.put("format", loadFormat(locale));
            row.put("configured", param(locale) != null);
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(Map<String, Object> body) {
        String locale = body.get("locale") == null ? null : String.valueOf(body.get("locale"));
        if (locale == null || !DEFAULTS.containsKey(locale)) {
            throw new ServiceException(422, "仅支持 zh-CN/en-US/ja-JP");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> format = (Map<String, String>) body.getOrDefault("format", DEFAULTS.get(locale));
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(format);
        } catch (Exception e) {
            throw new ServiceException(500, "格式序列化失败");
        }
        String key = PARAM_PREFIX + locale;
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_param WHERE PARAM_KEY=?", Integer.class, key);
        if (cnt != null && cnt > 0) {
            jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE=?, UPDATE_DATE=NOW() WHERE PARAM_KEY=?", json, key);
        } else {
            jdbc.update("INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, "
                            + "REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                            + "(UUID(), ?, ?, 'STRING', 'I18N', 'Locale 格式配置', 'i18n-admin', NOW(), '0', 0)",
                    key, json);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locale", locale);
        out.put("format", format);
        return out;
    }

    @Override
    public Map<String, Object> validate(String locale, Map<String, String> fields) {
        // C-5.7-02：Locale 未配置 → 回退默认 zh-CN 格式并记录事件
        Map<String, String> fmt = loadFormat(locale);
        boolean fellBack = param(locale) == null && fmt == DEFAULTS.get(locale) && locale != null
                && !locale.equals("zh-CN") && param(locale) == null;
        List<Map<String, Object>> errors = new ArrayList<>();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                String f = e.getKey();
                String v = e.getValue();
                if (v == null || v.isBlank()) {
                    continue;
                }
                String expected = null;
                boolean ok = true;
                if ("postalCode".equals(f)) {
                    expected = "邮编 " + fmt.get("postalRegex");
                    ok = v.matches(fmt.get("postalRegex"));
                } else if ("phone".equals(f)) {
                    expected = "电话 " + fmt.get("phoneRegex");
                    ok = v.matches(fmt.get("phoneRegex"));
                } else if ("date".equals(f)) {
                    expected = "日期 " + fmt.get("datePattern");
                    ok = v.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}");
                }
                if (!ok) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("field", f);
                    err.put("expected", expected);
                    err.put("actual", v);
                    errors.add(err);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", errors.isEmpty());
        out.put("errors", errors);
        out.put("fellBack", fellBack);
        if (fellBack) {
            recordFallback(locale);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> fallbackEvents(int limit) {
        return jdbc.queryForList(
                "SELECT PARAM_VALUE AS detail, UPDATE_DATE AS eventAt FROM erp_sys_param "
                        + "WHERE PARAM_KEY LIKE 'I18N_FALLBACK_EVENT_%' ORDER BY UPDATE_DATE DESC LIMIT ?",
                Math.min(Math.max(limit, 1), 200));
    }

    private void recordFallback(String locale) {
        jdbc.update("INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, "
                        + "REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                        + "(UUID(), ?, ?, 'STRING', 'I18N', '格式回退事件', 'i18n-engine', NOW(), '0', 0)",
                "I18N_FALLBACK_EVENT_" + System.currentTimeMillis(),
                "locale=" + locale + " 回退 zh-CN 格式（C-5.7-02）");
    }

    private Map<String, String> loadFormat(String locale) {
        String json = param(locale);
        if (json == null || json.isBlank()) {
            return DEFAULTS.getOrDefault(locale, DEFAULTS.get("zh-CN"));
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, Map.class);
            return m;
        } catch (Exception e) {
            return DEFAULTS.getOrDefault(locale, DEFAULTS.get("zh-CN"));
        }
    }

    private String param(String locale) {
        try {
            return jdbc.queryForObject(
                    "SELECT PARAM_VALUE FROM erp_sys_param WHERE PARAM_KEY=? AND DEL_FLAG='0'",
                    String.class, PARAM_PREFIX + locale);
        } catch (Exception e) {
            return null;
        }
    }
}
