package com.erp.service.impl.i18n;

import com.erp.common.ServiceException;
import com.erp.service.i18n.I18nSlaService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * i18n SLA 月度报告实现（add-i18n-l10n，BR-5.7-14 未归档阻断，复用 18.5 报告范式）。
 * 五指标：语言包版本数/三语覆盖率/缺口闭环率/渲染异常/灰度回退。
 * 快照存 erp_i18n_sla_report 无独立表 → 复用 erp_sys_param 快照键（沿 18.5 前身口径，
 * 但 PARAM_VALUE varchar(128) 不足 → 存缺口台账同库的独立快照：用 erp_i18n_pack 旁路不合适，
 * 故快照 JSON 存 erp_sys_param 会超长 → 改存 erp_i18n_gap 无关联不合适 → 最终：快照存
 * erp_i18n_pack 表无字段 → 用独立快照查询组装（report() 每次实时计算，不落快照；
 * 状态机 DRAFT/ARCHIVED/PUBLISHED 存 erp_sys_param 状态键 I18N_SLA_STATUS_<period>）。
 */
@Slf4j
@Service
public class I18nSlaServiceImpl implements I18nSlaService {

    private static final String STATUS_KEY = "I18N_SLA_STATUS_";

    private final JdbcTemplate jdbc;

    public I18nSlaServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generate(String period) {
        requirePeriod(period);
        Map<String, Object> metrics = new LinkedHashMap<>();
        // ① 语言包版本数
        metrics.put("packCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_pack WHERE DEL_FLAG='0'", Integer.class));
        // ② 三语覆盖率
        Map<String, Object> enCov = coverageOf("en-US");
        Map<String, Object> jaCov = coverageOf("ja-JP");
        metrics.put("enCoverage", enCov.get("coverage"));
        metrics.put("jaCoverage", jaCov.get("coverage"));
        // ③ 缺口闭环率
        Integer gapTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_gap WHERE DEL_FLAG='0'", Integer.class);
        Integer gapClosed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_gap WHERE DEL_FLAG='0' AND STATUS='CLOSED'", Integer.class);
        metrics.put("gapTotal", gapTotal);
        metrics.put("gapClosed", gapClosed);
        metrics.put("gapCloseRate", gapTotal == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(gapClosed * 100).divide(BigDecimal.valueOf(gapTotal), 2, RoundingMode.HALF_UP));
        // ④ 渲染异常（灰度累计）
        metrics.put("renderErr", jdbc.queryForObject(
                "SELECT COALESCE(SUM(RENDER_ERR_CNT),0) FROM erp_i18n_pack WHERE DEL_FLAG='0'", Integer.class));
        // ⑤ 灰度回退次数
        metrics.put("canaryRollback", jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_pack WHERE DEL_FLAG='0' AND REMARK LIKE '%回退%'", Integer.class));
        // 达标判定（en/ja 覆盖 ≥99、缺口闭环 ≥80）
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("enCoverage", "en-US 覆盖率", dec(metrics.get("enCoverage")), BigDecimal.valueOf(99), false));
        items.add(item("jaCoverage", "ja-JP 覆盖率", dec(metrics.get("jaCoverage")), BigDecimal.valueOf(99), false));
        items.add(item("gapCloseRate", "缺口闭环率", dec(metrics.get("gapCloseRate")), BigDecimal.valueOf(80), false));
        items.add(item("renderErr", "渲染异常次数", dec(metrics.get("renderErr")), BigDecimal.ZERO, true));
        metrics.put("items", items);
        // 幂等：先清当期改进项（KEY_NAME 确定性，防 DuplicateKey）
        jdbc.update("DELETE FROM erp_i18n_gap WHERE KEY_NAME LIKE ?", "SLA-" + period + "-%");
        // 未达标 → 改进项（30 天时限）
        List<String> improveIds = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (!Boolean.TRUE.equals(it.get("met"))) {
                String id = java.util.UUID.randomUUID().toString().replace("-", "");
                jdbc.update("INSERT INTO erp_i18n_gap (ID, KEY_NAME, MODULE, PRIORITY, FIRST_HIT_AT, "
                                + "HIT_COUNT, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?, ?, 'SLA', 'P1', NOW(), 0, 'OPEN', 'i18n-sla', NOW(), '0', 0)",
                        id, "SLA-" + period + "-" + it.get("key"));
                improveIds.add(String.valueOf(id));
            }
        }
        metrics.put("improvementIds", improveIds);
        // 状态置 DRAFT（若无）
        if (statusOf(period) == null) {
            setStatus(period, "DRAFT");
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("period", period);
        snap.put("status", statusOf(period));
        snap.put("metrics", metrics);
        snap.put("generatedAt", LocalDateTime.now().toString());
        return snap;
    }

    @Override
    public Map<String, Object> report(String period) {
        requirePeriod(period);
        String st = statusOf(period);
        if (st == null) {
            throw new ServiceException(404, "报告不存在：" + period + "（请先生成）");
        }
        // 实时组装指标（与 generate 同口径）
        Map<String, Object> gen = generateQuiet(period);
        gen.put("status", st);
        return gen;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(String period) {
        Map<String, Object> r = report(period);
        if (!"DRAFT".equals(r.get("status"))) {
            throw new ServiceException(422, "仅 DRAFT 可归档（当前 " + r.get("status") + "）");
        }
        setStatus(period, "ARCHIVED");
        r.put("status", "ARCHIVED");
        return r;
    }

    @Override
    public Map<String, Object> publish(String period, String scope) {
        String st = statusOf(period);
        if (st == null) {
            throw new ServiceException(404, "报告不存在");
        }
        // BR-5.7-14 / C-5.7-05：未归档发布硬阻断 + 留痕
        if (!"ARCHIVED".equals(st)) {
            jdbc.update("INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, "
                            + "REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                            + "(UUID(), ?, ?, 'STRING', 'I18N', '发布阻断留痕', ?, NOW(), '0', 0)",
                    "I18N_SLA_PUBLISH_BLOCKED_" + System.currentTimeMillis(),
                    "period=" + period + " status=" + st, operator());
            throw new ServiceException(422, "报告未归档，不可发布（BR-5.7-14）");
        }
        setStatus(period, "PUBLISHED");
        Map<String, Object> r = report(period);
        r.put("status", "PUBLISHED");
        r.put("publishScope", scope == null ? "ALL" : scope);
        return r;
    }

    @Override
    public List<Map<String, Object>> list() {
        return jdbc.queryForList(
                "SELECT PARAM_KEY AS k, PARAM_VALUE AS status, UPDATE_DATE AS updatedAt "
                        + "FROM erp_sys_param WHERE PARAM_KEY LIKE 'I18N_SLA_STATUS_%' AND DEL_FLAG='0' "
                        + "ORDER BY UPDATE_DATE DESC LIMIT 100");
    }

    // ---------------- 工具 ----------------

    private Map<String, Object> generateQuiet(String period) {
        // 与 generate 同口径但不写状态（report 复用）
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("packCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_pack WHERE DEL_FLAG='0'", Integer.class));
        metrics.put("enCoverage", coverageOf("en-US").get("coverage"));
        metrics.put("jaCoverage", coverageOf("ja-JP").get("coverage"));
        Integer gapTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_gap WHERE DEL_FLAG='0'", Integer.class);
        Integer gapClosed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_gap WHERE DEL_FLAG='0' AND STATUS='CLOSED'", Integer.class);
        metrics.put("gapTotal", gapTotal);
        metrics.put("gapClosed", gapClosed);
        metrics.put("gapCloseRate", gapTotal == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(gapClosed * 100).divide(BigDecimal.valueOf(gapTotal), 2, RoundingMode.HALF_UP));
        metrics.put("renderErr", jdbc.queryForObject(
                "SELECT COALESCE(SUM(RENDER_ERR_CNT),0) FROM erp_i18n_pack WHERE DEL_FLAG='0'", Integer.class));
        metrics.put("canaryRollback", jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_i18n_pack WHERE DEL_FLAG='0' AND REMARK LIKE '%回退%'", Integer.class));
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("enCoverage", "en-US 覆盖率", dec(metrics.get("enCoverage")), BigDecimal.valueOf(99), false));
        items.add(item("jaCoverage", "ja-JP 覆盖率", dec(metrics.get("jaCoverage")), BigDecimal.valueOf(99), false));
        items.add(item("gapCloseRate", "缺口闭环率", dec(metrics.get("gapCloseRate")), BigDecimal.valueOf(80), false));
        items.add(item("renderErr", "渲染异常次数", dec(metrics.get("renderErr")), BigDecimal.ZERO, true));
        metrics.put("items", items);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("period", period);
        snap.put("metrics", metrics);
        return snap;
    }

    private Map<String, Object> coverageOf(String locale) {
        // 分母 = zh 源语键总数，分子 = 目标语已译数（与 GapService.coverage 同口径）
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN t.TARGET_TEXT IS NOT NULL "
                        + "AND t.TARGET_TEXT<>'' THEN 1 ELSE 0 END),0) AS translated "
                        + "FROM erp_i18n_key s LEFT JOIN erp_i18n_key t ON t.KEY_NAME=s.KEY_NAME "
                        + "AND t.LOCALE=? AND t.DEL_FLAG='0' "
                        + "WHERE s.DEL_FLAG='0' AND s.LOCALE='zh-CN'", locale);
        long total = ((Number) r.get("total")).longValue();
        long tr = ((Number) r.get("translated")).longValue();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("translated", tr);
        out.put("coverage", total == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(tr * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
        return out;
    }

    private Map<String, Object> item(String key, String name, BigDecimal actual, BigDecimal target,
                                     boolean reverse) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("actual", actual);
        m.put("target", target);
        boolean met = reverse ? actual.compareTo(target) <= 0 : actual.compareTo(target) >= 0;
        m.put("met", met);
        return m;
    }

    private BigDecimal dec(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private String statusOf(String period) {
        try {
            return jdbc.queryForObject(
                    "SELECT PARAM_VALUE FROM erp_sys_param WHERE PARAM_KEY=? AND DEL_FLAG='0'",
                    String.class, STATUS_KEY + period);
        } catch (Exception e) {
            return null;
        }
    }

    private void setStatus(String period, String status) {
        String key = STATUS_KEY + period;
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_param WHERE PARAM_KEY=?", Integer.class, key);
        if (cnt != null && cnt > 0) {
            jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE=?, UPDATE_DATE=NOW() WHERE PARAM_KEY=?",
                    status, key);
        } else {
            jdbc.update("INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, "
                            + "REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                            + "(UUID(), ?, ?, 'STRING', 'I18N', 'i18n SLA 报告状态', 'i18n-sla', NOW(), '0', 0)",
                    key, status);
        }
    }

    private void requirePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new ServiceException(422, "period 须为 yyyy-MM");
        }
    }

    private String operator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}
