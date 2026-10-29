package com.erp.service.impl;

import com.erp.common.ServiceException;
import com.erp.security.AuditLogWriter;
import com.erp.service.SlaReportService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SLA 报告实现（add-sla-report-console design D2 指标聚合 / D3 生成幂等 / D4 发布双层 / D5 CSV）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlaReportServiceImpl implements SlaReportService {

    private final JdbcTemplate jdbc;
    private final AuditLogWriter auditLogWriter;
    private final SlaPublishViolationWriter violationWriter;

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 目标值常量（D2：5.6 域规格未定义，本 change 定义，偏差表记录） */
    private static final BigDecimal TARGET_AVAILABILITY = new BigDecimal("99.5");
    private static final long TARGET_P95_MS = 1000;
    private static final BigDecimal TARGET_ERROR_RATE = new BigDecimal("1");

    private static final String ST_DRAFT = "DRAFT";
    private static final String ST_ARCHIVED = "ARCHIVED";
    private static final String ST_PUBLISHED = "PUBLISHED";

    // ---------------- 生成（D2/D3） ----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateMonth(String period) {
        String p = normalizePeriod(period);
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT ID, STATUS FROM erp_sla_report WHERE PERIOD=? AND DEL_FLAG='0'", p);
        if (!existing.isEmpty()) {
            String st = String.valueOf(existing.get(0).get("STATUS"));
            if (!ST_DRAFT.equals(st)) {
                throw new ServiceException(422, "报告已归档锁定，不可重生成（版本锁定，BR-5.6-16 前置语义）");
            }
        }
        Map<String, Object> metrics = collectMetrics(p);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) metrics.get("rows");
        LocalDateTime now = LocalDateTime.now();
        String id;
        if (existing.isEmpty()) {
            id = uuid();
            jdbc.update("INSERT INTO erp_sla_report (ID, PERIOD, METRICS_JSON, DATA_AS_OF, STATUS, "
                            + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?,?,?,?,?, ?, NOW(), '0', 0)",
                    id, p, toJson(rows), now, ST_DRAFT, operator());
        } else {
            id = String.valueOf(existing.get(0).get("ID"));
            jdbc.update("UPDATE erp_sla_report SET METRICS_JSON=?, DATA_AS_OF=?, UPDATE_BY=?, "
                    + "UPDATE_DATE=NOW() WHERE ID=?", toJson(rows), now, operator(), id);
        }
        auditLogWriter.write("sla-report:" + p, "SLA_REPORT_GENERATED", null,
                "metrics=" + rows.size(), "月度 SLA 报告生成（" + p + "，数据截止 " + now.format(TS) + "）");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("period", p);
        out.put("status", ST_DRAFT);
        out.put("metricsCount", rows.size());
        out.put("dataAsOf", now.format(TS));
        return out;
    }

    @Override
    public Map<String, Object> sweepGenerate() {
        String target = LocalDate.now().minusMonths(1).format(PERIOD_FMT);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sla_report WHERE PERIOD=? AND DEL_FLAG='0'",
                Integer.class, target);
        if (exists != null && exists > 0) {
            // 补缺语义：任意状态已存在即跳过（D3——sweep 不覆盖人工操作）
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generated", 0);
            out.put("skipped", 1);
            out.put("period", target);
            return out;
        }
        Map<String, Object> gen = generateMonth(target);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", 1);
        out.put("skipped", 0);
        out.put("period", target);
        out.put("id", gen.get("id"));
        return out;
    }

    // ---------------- 指标聚合（D2） ----------------

    private Map<String, Object> collectMetrics(String period) {
        LocalDateTime from = LocalDateTime.parse(period + "-01T00:00:00");
        LocalDateTime to = from.plusMonths(1);
        List<Map<String, Object>> rows = new ArrayList<>();

        // ① 接口调用类（erp_intf_call_log）
        Map<String, Object> call = jdbc.queryForList(
                "SELECT COUNT(*) AS total, "
                        + "SUM(CASE WHEN RESP_CODE BETWEEN 200 AND 299 THEN 1 ELSE 0 END) AS ok, "
                        + "AVG(COST_MS) AS avgCost "
                        + "FROM erp_intf_call_log WHERE DEL_FLAG='0' AND CALL_AT >= ? AND CALL_AT < ?",
                from, to).get(0);
        long total = num(call.get("total"));
        long ok = num(call.get("ok"));
        if (total == 0) {
            // 空月兜底（spec：不适用而非 0%）
            rows.add(metric("AVAILABILITY", "接口可用率", "不适用", "%",
                    "99.5%", null, "当月无接口调用"));
            rows.add(metric("ERROR_RATE", "接口错误率", "不适用", "%",
                    "≤1%", null, "当月无接口调用"));
            rows.add(metric("P95_MS", "接口响应P95", "不适用", "ms",
                    "≤1000", null, "当月无接口调用"));
            rows.add(metric("AVG_MS", "接口平均响应", "不适用", "ms",
                    null, null, "当月无接口调用"));
        } else {
            BigDecimal availability = BigDecimal.valueOf(ok)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            BigDecimal errorRate = BigDecimal.valueOf(total - ok)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            rows.add(metric("AVAILABILITY", "接口可用率", availability.stripTrailingZeros().toPlainString(),
                    "%", "99.5%", availability.compareTo(TARGET_AVAILABILITY) >= 0,
                    ok + "/" + total + " 次 2xx"));
            rows.add(metric("ERROR_RATE", "接口错误率", errorRate.stripTrailingZeros().toPlainString(),
                    "%", "≤1%", errorRate.compareTo(TARGET_ERROR_RATE) <= 0, null));
            // P95：偏移法（D2）
            int offset = (int) Math.max(Math.floor(0.95 * total) - 1, 0);
            Long p95 = jdbc.queryForObject(
                    "SELECT COST_MS FROM erp_intf_call_log WHERE DEL_FLAG='0' AND CALL_AT >= ? AND CALL_AT < ? "
                            + "ORDER BY COST_MS DESC LIMIT 1 OFFSET ?", Long.class, from, to, offset);
            rows.add(metric("P95_MS", "接口响应P95", p95 == null ? "不适用" : String.valueOf(p95),
                    "ms", "≤1000", p95 != null && p95 <= TARGET_P95_MS, null));
            Object avg = call.get("avgCost");
            rows.add(metric("AVG_MS", "接口平均响应",
                    avg == null ? "不适用" : new BigDecimal(String.valueOf(avg))
                            .setScale(1, RoundingMode.HALF_UP).toPlainString(),
                    "ms", null, null, null));
        }
        rows.add(metric("CALL_COUNT", "接口调用量", String.valueOf(total), "次", null, null, null));

        // ② 审计写入量（hr_change_log）
        long auditRows = num(jdbc.queryForList(
                "SELECT COUNT(*) AS c FROM hr_change_log WHERE CHANGE_AT >= ? AND CHANGE_AT < ?",
                from, to).get(0).get("c"));
        rows.add(metric("AUDIT_WRITES", "审计写入量", String.valueOf(auditRows), "条", null, null, null));

        // ③ 各域安全事件量（六类计数）
        rows.add(metric("GRANTS", "权限授予数", String.valueOf(countInRange(
                "erp_admin_grant_ledger", "CREATE_DATE", from, to)), "笔", null, null, null));
        rows.add(metric("RECLAIMS", "权限回收数", String.valueOf(countStatusInRange(
                "erp_admin_grant_ledger", "STATUS", "RECLAIMED", from, to)), "笔", null, null, null));
        rows.add(metric("AUTHZ_INCIDENTS", "越权探测事件", String.valueOf(countInRange(
                "erp_authz_incident", "CREATE_DATE", from, to)), "起", null, null, null));
        rows.add(metric("BG_REQUESTS", "紧急权限申请", String.valueOf(countInRange(
                "erp_admin_bg_request", "CREATE_DATE", from, to)), "笔", null, null, null));
        rows.add(metric("UAR_TASKS", "权限复核任务", String.valueOf(countInRange(
                "erp_admin_uar_task", "CREATE_DATE", from, to)), "个", null, null, null));
        rows.add(metric("TAMPER_ATTEMPTS", "防篡改尝试", String.valueOf(countInRange(
                "erp_ops_audit_tamper", "ATTEMPT_AT", from, to)), "次", null, null, null));
        rows.add(metric("FORENSIC_REQUESTS", "取证调阅申请", String.valueOf(countInRange(
                "erp_ops_forensic_request", "CREATE_DATE", from, to)), "笔", null, null, null));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        return out;
    }

    private Map<String, Object> metric(String code, String name, Object value, String unit,
                                       String target, Boolean pass, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("value", value);
        m.put("unit", unit);
        m.put("target", target);
        m.put("pass", pass);
        m.put("note", note);
        return m;
    }

    private long countInRange(String table, String timeCol, LocalDateTime from, LocalDateTime to) {
        return num(jdbc.queryForList(
                "SELECT COUNT(*) AS c FROM " + table + " WHERE " + timeCol + " >= ? AND " + timeCol + " < ?",
                from, to).get(0).get("c"));
    }

    private long countStatusInRange(String table, String statusCol, String status,
                                    LocalDateTime from, LocalDateTime to) {
        return num(jdbc.queryForList(
                "SELECT COUNT(*) AS c FROM " + table + " WHERE " + statusCol + "=? AND "
                        + timeColSafe(table) + " >= ? AND " + timeColSafe(table) + " < ?",
                status, from, to).get(0).get("c"));
    }

    private String timeColSafe(String table) {
        // 回收台账按 UPDATE_DATE 语义更准（回收时刻）；其余表无此调用
        return "UPDATE_DATE";
    }

    // ---------------- 归档 / 发布 / 下载（D4/D5） ----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(String id) {
        Map<String, Object> rep = requireReport(id);
        if (!ST_DRAFT.equals(rep.get("STATUS"))) {
            throw new ServiceException(422, "仅草稿状态可归档（当前：" + rep.get("STATUS") + "）");
        }
        jdbc.update("UPDATE erp_sla_report SET STATUS=?, ARCHIVE_BY=?, ARCHIVE_AT=NOW(), "
                        + "UPDATE_BY=?, UPDATE_DATE=NOW() WHERE ID=?",
                ST_ARCHIVED, operator(), operator(), id);
        auditLogWriter.write("sla-report:" + rep.get("PERIOD"), "SLA_REPORT_ARCHIVED",
                ST_DRAFT, ST_ARCHIVED, "月度 SLA 报告归档（版本锁定）");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(String id) {
        Map<String, Object> rep = requireReport(id);
        String status = String.valueOf(rep.get("STATUS"));
        if (ST_PUBLISHED.equals(status)) {
            throw new ServiceException(422, "报告已发布，不可重复发布");
        }
        if (!ST_ARCHIVED.equals(status)) {
            // D4：违规留痕（REQUIRES_NEW，独立于本事务的 422 回滚）→ 再抛 422
            violationWriter.recordPublishViolation(id, status,
                    String.valueOf(rep.get("CREATE_BY")), operator());
            throw new ServiceException(422, "报告未归档，不可发布（BR-5.6-16 / C-5.6-05）");
        }
        jdbc.update("UPDATE erp_sla_report SET STATUS=?, PUBLISH_BY=?, PUBLISH_AT=NOW(), "
                        + "PUBLISH_COUNT=PUBLISH_COUNT+1, UPDATE_BY=?, UPDATE_DATE=NOW() WHERE ID=?",
                ST_PUBLISHED, operator(), operator(), id);
        auditLogWriter.write("sla-report:" + rep.get("PERIOD"), "SLA_REPORT_PUBLISHED",
                ST_ARCHIVED, ST_PUBLISHED, "月度 SLA 报告对外发布");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> download(String id) {
        Map<String, Object> rep = requireReport(id);
        String status = String.valueOf(rep.get("STATUS"));
        if (ST_DRAFT.equals(status)) {
            throw new ServiceException(422, "报告未定稿（DRAFT），不可下载");
        }
        String csv = renderCsv(rep);
        auditLogWriter.write("sla-report:" + rep.get("PERIOD"), "SLA_REPORT_DOWNLOADED",
                status, null, "SLA 报告 CSV 下载");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("filename", "sla-report-" + rep.get("PERIOD") + ".csv");
        out.put("content", csv.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** CSV（D5：固定列序 + UTF-8 BOM，指标由 METRICS_JSON 确定性渲染） */
    @SuppressWarnings("unchecked")
    private String renderCsv(Map<String, Object> rep) {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿'); // UTF-8 BOM（Excel 兼容）
        sb.append("指标编码,指标名称,指标值,单位,目标值,是否达标,数据截止时间\r\n");
        String asOf = rep.get("DATA_AS_OF") == null ? ""
                : String.valueOf(rep.get("DATA_AS_OF"));
        List<Map<String, Object>> rows;
        try {
            rows = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(String.valueOf(rep.get("METRICS_JSON")),
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                            });
        } catch (Exception e) {
            rows = List.of();
        }
        for (Map<String, Object> m : rows) {
            sb.append(csvCell(str(m.get("code")))).append(',');
            sb.append(csvCell(str(m.get("name")))).append(',');
            sb.append(csvCell(str(m.get("value")))).append(',');
            sb.append(csvCell(str(m.get("unit")))).append(',');
            sb.append(csvCell(str(m.get("target")))).append(',');
            Object pass = m.get("pass");
            sb.append(csvCell(pass == null ? "不适用" : (Boolean.TRUE.equals(pass) ? "达标" : "未达标"))).append(',');
            sb.append(csvCell(asOf)).append("\r\n");
        }
        return sb.toString();
    }

    private String csvCell(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    // ---------------- 查询 ----------------

    @Override
    public Map<String, Object> list(String period, String status, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 || size > 500 ? 50 : size;
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, PERIOD AS period, METRICS_JSON AS metricsJson, DATA_AS_OF AS dataAsOf, "
                        + "STATUS AS status, ARCHIVE_BY AS archiveBy, ARCHIVE_AT AS archiveAt, "
                        + "PUBLISH_BY AS publishBy, PUBLISH_AT AS publishAt, PUBLISH_COUNT AS publishCount, "
                        + "CREATE_BY AS createBy, CREATE_DATE AS createDate "
                        + "FROM erp_sla_report WHERE DEL_FLAG='0' ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(period)) {
            sql.append("AND PERIOD = ? ");
            args.add(period);
        }
        if (StringUtils.hasText(status)) {
            sql.append("AND STATUS = ? ");
            args.add(status);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + sql + ") t",
                Integer.class, args.toArray());
        sql.append("ORDER BY PERIOD DESC LIMIT ? OFFSET ? ");
        args.add(s);
        args.add((p - 1) * s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", jdbc.queryForList(sql.toString(), args.toArray()));
        return out;
    }

    // ---------------- 内部 ----------------

    private Map<String, Object> requireReport(String id) {
        Map<String, Object> rep = jdbc.queryForList(
                "SELECT ID, PERIOD, STATUS, CREATE_BY, METRICS_JSON, DATA_AS_OF "
                        + "FROM erp_sla_report WHERE ID=? AND DEL_FLAG='0'",
                id).stream().findFirst().orElse(null);
        if (rep == null) {
            throw new ServiceException(404, "报告不存在：" + id);
        }
        return rep;
    }

    private String normalizePeriod(String period) {
        if (!StringUtils.hasText(period) || !period.matches("\\d{4}-\\d{2}")) {
            throw new ServiceException(422, "月份格式须为 YYYY-MM");
        }
        try {
            LocalDate.parse(period + "-01");
        } catch (Exception e) {
            throw new ServiceException(422, "月份不合法：" + period);
        }
        return period;
    }

    private long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String toJson(List<Map<String, Object>> rows) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows);
        } catch (Exception e) {
            throw new ServiceException(500, "指标序列化失败");
        }
    }

    private String operator() {
        String me = SecurityUtils.getCurrentUserId();
        return me == null ? "system" : me;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
