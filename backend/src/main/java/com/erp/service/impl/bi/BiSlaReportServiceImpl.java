package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiSlaMetricDao;
import com.erp.dao.bi.BiSlaReportDao;
import com.erp.entity.bi.BiSlaMetric;
import com.erp.entity.bi.BiSlaReport;
import com.erp.security.AuditLogWriter;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.bi.BiSlaReportService;
import com.erp.service.bi.PdfFileWriter;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报表 SLA 服务报告（add-bi-sla-report，spec bi-sla-report，4.10 流程五）。
 * 五指标月度采集（快照固化+部分数据标注 BR-4.10-29）→ 报告编制（未达标三要素 BR-4.10-27）
 * → 三方审核归档（版本锁定 BR-4.10-28）→ 归档后发布（未归档硬阻断 BR-4.10-26/C-4.10-04）。
 * 与 16.9 sla-report-management（接口域）完全隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BiSlaReportServiceImpl implements BiSlaReportService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter PERIOD_T = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 五指标目标值（design D1：QUERY_P95 为上限秒，其余为下限百分比；DAILY_REACH 无目标） */
    private static final Map<String, BigDecimal> TARGETS = Map.of(
            BiSlaMetric.K_AVAILABILITY, new BigDecimal("99.5"),
            BiSlaMetric.K_QUERY_P95, BigDecimal.TEN,
            BiSlaMetric.K_REFRESH_ONTIME, new BigDecimal("99"),
            BiSlaMetric.K_EXPORT_SUCCESS, new BigDecimal("99"));
    private static final Map<String, String> UNITS = Map.of(
            BiSlaMetric.K_AVAILABILITY, "%",
            BiSlaMetric.K_QUERY_P95, "s",
            BiSlaMetric.K_REFRESH_ONTIME, "%",
            BiSlaMetric.K_EXPORT_SUCCESS, "%",
            BiSlaMetric.K_DAILY_REACH, "%");

    private final BiSlaMetricDao metricDao;
    private final BiSlaReportDao reportDao;
    private final JdbcTemplate jdbc;
    private final ApprovalEngine approvalEngine;
    private final AuditLogWriter auditLogWriter;
    private final PdfFileWriter pdfWriter = new PdfFileWriter();

    // ================= 指标采集 =================

    @Override
    public Map<String, Object> collectMetrics(String period) {
        String p = normalizePeriod(period);
        Map<String, Object> out = new LinkedHashMap<>();
        int ok = 0;
        int failed = 0;
        // ① 报表可用率（调度成功率代理，design D2 口径说明）
        try {
            collectOne(p, BiSlaMetric.K_AVAILABILITY, availabilitySql(p));
            ok++;
        } catch (Exception e) {
            failed++;
            log.warn("sla collect AVAILABILITY failed: {}", e.getMessage());
        }
        // ② 查询响应 P95（MySQL 8.0 无 PERCENTILE_CONT，用 OFFSET 取分位）
        try {
            collectQueryP95(p);
            ok++;
        } catch (Exception e) {
            failed++;
            log.warn("sla collect QUERY_P95 failed: {}", e.getMessage());
        }
        // ③ 数据刷新及时率
        try {
            collectRefresh(p);
            ok++;
        } catch (Exception e) {
            failed++;
            log.warn("sla collect REFRESH_ONTIME failed: {}", e.getMessage());
        }
        // ④ 导出成功率
        try {
            collectOne(p, BiSlaMetric.K_EXPORT_SUCCESS, exportSuccessSql(p));
            ok++;
        } catch (Exception e) {
            failed++;
            log.warn("sla collect EXPORT_SUCCESS failed: {}", e.getMessage());
        }
        // ⑤ 日报触达率
        try {
            collectOne(p, BiSlaMetric.K_DAILY_REACH, dailyReachSql(p));
            ok++;
        } catch (Exception e) {
            failed++;
            log.warn("sla collect DAILY_REACH failed: {}", e.getMessage());
        }
        out.put("period", p);
        out.put("success", ok);
        out.put("failed", failed);
        return out;
    }

    /** 单指标：执行聚合 SQL → upsert 快照（幂等） */
    private void collectOne(String period, String metricKey, String aggSql) {
        Map<String, Object> row = jdbc.queryForMap(aggSql);
        BigDecimal value = dec(row.get("v"));
        BigDecimal target = TARGETS.get(metricKey);
        // 部分数据判定（BR-4.10-29）：partial=N 且 missing 非空 → Y
        String partial = num(row.get("partial")) > 0 ? "Y" : "N";
        String missing = str(row.get("missing"));
        upsertMetric(period, metricKey, value, target, UNITS.get(metricKey), partial, missing, null);
    }

    /** 刷新及时率：按时（≤当日 06:00 完成）批次占比 + 缺失日判定 */
    private void collectRefresh(String period) {
        LocalDate start = monthStart(period);
        LocalDate end = monthEnd(period);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "SUM(CASE WHEN FINISHED_AT <= DATE_ADD(BATCH_DATE, INTERVAL 6 HOUR) THEN 1 ELSE 0 END) AS ontime, "
                        + "COUNT(DISTINCT BATCH_DATE) AS days "
                        + "FROM erp_bi_extract_run WHERE DEL_FLAG='0' AND BATCH_DATE >= ? AND BATCH_DATE <= ?",
                start, end);
        long total = num(row.get("total"));
        long ontime = num(row.get("ontime"));
        BigDecimal value = total == 0 ? null
                : BigDecimal.valueOf(ontime * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        // 缺失日：当月自然日 vs 有批次记录的日
        List<String> missing = missingDays(start, end,
                "SELECT DISTINCT BATCH_DATE FROM erp_bi_extract_run WHERE DEL_FLAG='0' "
                        + "AND BATCH_DATE >= ? AND BATCH_DATE <= ?");
        upsertMetric(period, BiSlaMetric.K_REFRESH_ONTIME, value,
                TARGETS.get(BiSlaMetric.K_REFRESH_ONTIME), "%",
                (total == 0 || !missing.isEmpty()) ? "Y" : "N",
                missing.isEmpty() ? null : String.join(",", missing), null);
    }

    /** 可用率：调度成功占比（KPI 快照日 + 日报推送日 + 抽取批次日 的成功日 / 应有日；design D2 代理口径） */
    private String availabilitySql(String period) {
        LocalDate start = monthStart(period);
        LocalDate end = monthEnd(period);
        long days = end.getDayOfMonth();
        return "SELECT ROUND(COUNT(DISTINCT d.d) * 100 / " + days + ", 2) AS v, "
                + "CASE WHEN COUNT(DISTINCT d.d) < " + days + " THEN 1 ELSE 0 END AS partial, '' AS missing "
                + "FROM (SELECT SNAP_DATE AS d FROM erp_bi_kpi_snapshot WHERE DEL_FLAG='0' "
                + " AND SNAP_DATE >= '" + start + "' AND SNAP_DATE <= '" + end + "' AND ERROR_MSG IS NULL "
                + "UNION SELECT PUSH_DATE FROM erp_bi_report_push_log WHERE DEL_FLAG='0' "
                + " AND SEND_STATUS IN ('SENT','DEGRADED') AND PUSH_DATE >= '" + start + "' "
                + " AND PUSH_DATE <= '" + end + "') d";
    }

    /** 查询 P95：即席查询 DONE 任务 EXEC_MS 百分位（MySQL 8.0 无分析函数，OFFSET 取分位再转秒） */
    private void collectQueryP95(String period) {
        LocalDate start = monthStart(period);
        LocalDate end = monthEnd(period);
        String where = "DEL_FLAG='0' AND STATUS='DONE' AND CREATE_DATE >= '" + start
                + "' AND CREATE_DATE < '" + end.plusDays(1) + "'";
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_adhoc_task WHERE " + where, Long.class);
        BigDecimal value = null;
        if (total != null && total > 0) {
            // P95 行序号 = ceil(0.95 × n)，OFFSET = 序号-1（无并列近似，快照口径一致即可）
            long offset = (long) Math.ceil(total * 0.95) - 1;
            Long ms = jdbc.queryForObject(
                    "SELECT EXEC_MS FROM erp_bi_adhoc_task WHERE " + where
                            + " ORDER BY EXEC_MS LIMIT 1 OFFSET " + offset, Long.class);
            value = ms == null ? null
                    : BigDecimal.valueOf(ms).divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
        }
        upsertMetric(period, BiSlaMetric.K_QUERY_P95, value, TARGETS.get(BiSlaMetric.K_QUERY_P95),
                "s", (total == null || total == 0) ? "Y" : "N", null, null);
    }

    /** 导出成功率 */
    private String exportSuccessSql(String period) {
        LocalDate start = monthStart(period);
        LocalDate end = monthEnd(period);
        return "SELECT ROUND(SUM(CASE WHEN STATUS='SUCCESS' THEN 1 ELSE 0 END) * 100 "
                + "/ NULLIF(COUNT(*), 0), 2) AS v, "
                + "CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END AS partial, '' AS missing "
                + "FROM erp_bi_export_task WHERE DEL_FLAG='0' AND TASK_TYPE='EXPORT' "
                + "AND CREATE_DATE >= '" + start + "' AND CREATE_DATE < '" + end.plusDays(1) + "'";
    }

    /** 日报触达率（BR-4.10-38 月度汇总） */
    private String dailyReachSql(String period) {
        LocalDate start = monthStart(period);
        return "SELECT ROUND(SUM(CASE WHEN SEND_STATUS IN ('SENT','DEGRADED') THEN 1 ELSE 0 END) * 100 "
                + "/ NULLIF(COUNT(*), 0), 2) AS v, "
                + "CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END AS partial, '' AS missing "
                + "FROM erp_bi_report_push_log WHERE DEL_FLAG='0' AND PUSH_DATE >= '" + start + "' "
                + "AND PUSH_DATE < '" + start.plusMonths(1) + "'";
    }

    /** 当月缺失日清单（禁插值，BR-4.10-29） */
    private List<String> missingDays(LocalDate start, LocalDate end, String existSql) {
        List<String> exists = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(existSql, start, end)) {
            Object d = r.get("BATCH_DATE");
            if (d instanceof LocalDate) {
                exists.add(d.toString());
            } else if (d != null) {
                exists.add(String.valueOf(d).substring(0, 10));
            }
        }
        List<String> missing = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (!exists.contains(d.toString())) {
                missing.add(d.toString());
            }
        }
        return missing;
    }

    private void upsertMetric(String period, String metricKey, BigDecimal value, BigDecimal target,
                              String unit, String partial, String missing, String error) {
        BiSlaMetric m = metricDao.selectOne(new QueryWrapper<BiSlaMetric>()
                .eq("PERIOD", period).eq("METRIC_KEY", metricKey).eq("DEL_FLAG", "0"));
        if (m == null) {
            m = new BiSlaMetric();
            m.setId(uuid());
            m.setPeriod(period);
            m.setMetricKey(metricKey);
            m.setMetricValue(value);
            m.setTarget(target);
            m.setUnit(unit);
            m.setPartialFlag(partial);
            m.setMissingDates(missing);
            m.setErrorMsg(error);
            metricDao.insert(m);
        } else {
            m.setMetricValue(value);
            m.setPartialFlag(partial);
            m.setMissingDates(missing);
            m.setErrorMsg(error);
            m.setUpdateBy("bi-sla-collector");
            metricDao.updateById(m);
        }
    }

    @Override
    public Map<String, Object> metrics(String period) {
        String p = normalizePeriod(period);
        List<Map<String, Object>> cur = jdbc.queryForList(
                "SELECT METRIC_KEY AS metricKey, METRIC_VALUE AS metricValue, TARGET AS target, "
                        + "UNIT AS unit, PARTIAL_FLAG AS partialFlag, MISSING_DATES AS missingDates "
                        + "FROM erp_bi_sla_metric WHERE PERIOD=? AND DEL_FLAG='0'", p);
        // 近 6 月趋势（含当月）
        String start = YearMonth.parse(p).minusMonths(5).format(PERIOD_T);
        List<Map<String, Object>> trend = jdbc.queryForList(
                "SELECT PERIOD AS period, METRIC_KEY AS metricKey, METRIC_VALUE AS metricValue "
                        + "FROM erp_bi_sla_metric WHERE PERIOD >= ? AND PERIOD <= ? AND DEL_FLAG='0' "
                        + "ORDER BY PERIOD", start, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", p);
        out.put("metrics", cur);
        out.put("trend", trend);
        return out;
    }

    // ================= 报告编制 =================

    @Override
    public List<Map<String, Object>> listReports() {
        return jdbc.queryForList(
                "SELECT ID AS id, PERIOD AS period, VERSION AS version, STATUS AS status, "
                        + "ARCHIVE_AT AS archiveAt, PUBLISH_AT AS publishAt, CREATE_DATE AS createDate "
                        + "FROM erp_bi_sla_report WHERE DEL_FLAG='0' ORDER BY PERIOD DESC, VERSION DESC");
    }

    @Override
    public Map<String, Object> getReport(String period) {
        BiSlaReport r = latest(normalizePeriod(period));
        if (r == null) {
            throw new ServiceException(404, "报告不存在，请先生成");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("period", r.getPeriod());
        out.put("version", r.getVersion());
        out.put("status", r.getStatus());
        out.put("metricsJson", r.getMetricsJson());
        out.put("rootCauseJson", r.getRootCauseJson());
        out.put("improveJson", r.getImproveJson());
        out.put("approvalId", r.getApprovalId());
        out.put("rejectReason", r.getRejectReason());
        out.put("archiveAt", r.getArchiveAt());
        out.put("publishAt", r.getPublishAt());
        out.put("publishJson", r.getPublishJson());
        // 版本历史
        out.put("versions", jdbc.queryForList(
                "SELECT VERSION AS version, STATUS AS status, ARCHIVE_AT AS archiveAt "
                        + "FROM erp_bi_sla_report WHERE PERIOD=? AND DEL_FLAG='0' ORDER BY VERSION DESC",
                r.getPeriod()));
        return out;
    }

    @Override
    public Map<String, Object> generateReport(String period) {
        String p = normalizePeriod(period);
        BiSlaReport exist = latest(p);
        if (exist != null && !BiSlaReport.ST_DRAFT.equals(exist.getStatus())) {
            throw new ServiceException(422, "该月报告已进入流程（" + exist.getStatus() + "），不可重新生成；修改请走新版本");
        }
        // 无快照先采集
        Long snap = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_sla_metric WHERE PERIOD=? AND DEL_FLAG='0'",
                Long.class, p);
        if (snap == null || snap == 0) {
            collectMetrics(p);
        }
        List<Map<String, Object>> snaps = jdbc.queryForList(
                "SELECT METRIC_KEY AS metricKey, METRIC_VALUE AS metricValue, TARGET AS target, "
                        + "UNIT AS unit, PARTIAL_FLAG AS partialFlag "
                        + "FROM erp_bi_sla_metric WHERE PERIOD=? AND DEL_FLAG='0'", p);
        // 三色判定（QUERY_P95 反向：≤ target 达标）
        List<Map<String, Object>> overview = new ArrayList<>();
        for (Map<String, Object> s : snaps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metricKey", s.get("metricKey"));
            m.put("metricValue", s.get("metricValue"));
            m.put("target", s.get("target"));
            m.put("unit", s.get("unit"));
            m.put("partialFlag", s.get("partialFlag"));
            m.put("status", judgeStatus(str(s.get("metricKey")), dec(s.get("metricValue")), dec(s.get("target"))));
            overview.add(m);
        }
        BiSlaReport r;
        if (exist == null) {
            r = new BiSlaReport();
            r.setId(uuid());
            r.setPeriod(p);
            r.setVersion(1);
            r.setStatus(BiSlaReport.ST_DRAFT);
            r.setMetricsJson(toJson(overview));
            reportDao.insert(r);
        } else {
            r = exist;
            r.setMetricsJson(toJson(overview));
            r.setUpdateBy(operator());
            reportDao.updateById(r);
        }
        auditLogWriter.write("bi-sla:" + p, "BI_SLA_REPORT_GENERATED", null, r.getStatus(),
                "月度报表 SLA 报告生成（" + p + "）");
        return Map.of("id", r.getId(), "period", p, "status", r.getStatus(), "overview", overview);
    }

    /** 三色：QUERY_P95 上限反向，其余下限；null=未采集（灰） */
    private String judgeStatus(String key, BigDecimal v, BigDecimal target) {
        if (v == null || target == null) {
            return "UNKNOWN";
        }
        if (BiSlaMetric.K_QUERY_P95.equals(key)) {
            return v.compareTo(target) <= 0 ? "ACHIEVED" : "BELOW";
        }
        if (BiSlaMetric.K_DAILY_REACH.equals(key)) {
            return "INFO";
        }
        int cmp = v.compareTo(target);
        if (cmp >= 0) {
            return "ACHIEVED";
        }
        // 黄档：目标 99/99.5 → 95% 目标以上算 WARN，否则 BELOW；10 秒上限已 above 处理
        BigDecimal warnLine = target.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);
        return v.compareTo(warnLine) >= 0 ? "WARN" : "BELOW";
    }

    @Override
    public void saveRootCause(String period, List<Map<String, Object>> items) {
        BiSlaReport r = draftOf(normalizePeriod(period));
        r.setRootCauseJson(toJson(items));
        r.setUpdateBy(operator());
        reportDao.updateById(r);
    }

    @Override
    public void saveImprove(String period, Map<String, Object> improve) {
        BiSlaReport r = draftOf(normalizePeriod(period));
        r.setImproveJson(toJson(improve));
        r.setUpdateBy(operator());
        reportDao.updateById(r);
    }

    // ================= 三方审核 =================

    @Override
    public Map<String, Object> submit(String period) {
        BiSlaReport r = draftOf(normalizePeriod(period));
        // BR-4.10-27：未达标项三要素齐备否则 422；全达标免校验
        List<Map<String, Object>> missing = unachievedMissingElements(r);
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "未达标指标三要素缺失，待补全：" + toJson(missing));
        }
        // 三方串行：数据治理 → IT 负责人（BI 设计师为发起人，不自签——引擎职责分离守护）
        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_DATA_GOV", "数据治理专员-SLA审核")),
                List.of(ApprovalNodeSpec.sign("ROLE_ADMIN", "IT-SLA审批")));
        var inst = approvalEngine.submit("BiSlaReport", r.getId(),
                "月度报表 SLA 报告审核：" + r.getPeriod(), "ROLE_ADMIN", chain);
        r.setStatus(BiSlaReport.ST_PENDING);
        r.setApprovalId(inst.getId());
        r.setUpdateBy(operator());
        reportDao.updateById(r);
        return Map.of("id", r.getId(), "period", r.getPeriod(),
                "status", r.getStatus(), "approvalInstanceId", inst.getId());
    }

    /** 未达标项缺三要素清单（BR-4.10-27） */
    private List<Map<String, Object>> unachievedMissingElements(BiSlaReport r) {
        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> overview = parse(r.getMetricsJson());
        Map<String, Map<String, Object>> causeByKey = new LinkedHashMap<>();
        for (Map<String, Object> c : parse(r.getRootCauseJson())) {
            causeByKey.put(str(c.get("metricKey")), c);
        }
        for (Map<String, Object> m : overview) {
            String st = str(m.get("status"));
            if ("ACHIEVED".equals(st) || "INFO".equals(st) || "UNKNOWN".equals(st)) {
                continue;
            }
            Map<String, Object> c = causeByKey.get(str(m.get("metricKey")));
            boolean complete = c != null
                    && notBlank(str(c.get("rootCause"))) && notBlank(str(c.get("owner")))
                    && notBlank(str(c.get("dueDate")));
            if (!complete) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("metricKey", m.get("metricKey"));
                item.put("missing", c == null ? "rootCause,owner,dueDate"
                        : String.join(",", blankFields(c)));
                missing.add(item);
            }
        }
        return missing;
    }

    private List<String> blankFields(Map<String, Object> c) {
        List<String> b = new ArrayList<>();
        if (!notBlank(str(c.get("rootCause")))) {
            b.add("rootCause");
        }
        if (!notBlank(str(c.get("owner")))) {
            b.add("owner");
        }
        if (!notBlank(str(c.get("dueDate")))) {
            b.add("dueDate");
        }
        return b;
    }

    /** 审批回调入口（BiSlaReportApprovalCallback 调用）：全过→APPROVED+归档版本锁定 */
    public void activateOnApproved(String reportId) {
        BiSlaReport r = reportDao.selectById(reportId);
        if (r == null) {
            return;
        }
        r.setStatus(BiSlaReport.ST_ARCHIVED);
        r.setArchiveBy(operator());
        r.setArchiveAt(LocalDateTime.now());
        r.setUpdateBy("bi-sla-approval");
        reportDao.updateById(r);
        auditLogWriter.write("bi-sla:" + r.getPeriod(), "BI_SLA_REPORT_ARCHIVED", null, "ARCHIVED",
                "SLA 报告审核通过并归档（版本锁定）");
    }

    /** 审批驳回回调：回 DRAFT */
    public void rejectOnCallback(String reportId, String reason) {
        BiSlaReport r = reportDao.selectById(reportId);
        if (r == null) {
            return;
        }
        r.setStatus(BiSlaReport.ST_DRAFT);
        r.setRejectReason(reason);
        r.setUpdateBy("bi-sla-approval");
        reportDao.updateById(r);
    }

    // ================= 归档锁定与新版本 =================

    @Override
    public Map<String, Object> newVersion(String period) {
        String p = normalizePeriod(period);
        BiSlaReport cur = latest(p);
        if (cur == null) {
            throw new ServiceException(404, "报告不存在");
        }
        if (BiSlaReport.ST_DRAFT.equals(cur.getStatus())
                || BiSlaReport.ST_PENDING.equals(cur.getStatus())) {
            throw new ServiceException(422, "当前版本尚在流程中，不可新开版本");
        }
        // 归档/发布后修改 → VERSION+1 新 DRAFT 行（BR-4.10-28 版本留痕）
        BiSlaReport n = new BiSlaReport();
        n.setId(uuid());
        n.setPeriod(p);
        n.setVersion(cur.getVersion() + 1);
        n.setStatus(BiSlaReport.ST_DRAFT);
        n.setMetricsJson(cur.getMetricsJson());
        n.setRootCauseJson(cur.getRootCauseJson());
        n.setImproveJson(cur.getImproveJson());
        n.setCreateBy(operator());
        reportDao.insert(n);
        auditLogWriter.write("bi-sla:" + p, "BI_SLA_NEW_VERSION", String.valueOf(cur.getVersion()),
                String.valueOf(n.getVersion()), "归档后修改生成新版本");
        return Map.of("id", n.getId(), "period", p, "version", n.getVersion(), "status", n.getStatus());
    }

    // ================= 发布 =================

    @Override
    public Map<String, Object> publish(String period) {
        String p = normalizePeriod(period);
        BiSlaReport r = latest(p);
        if (r == null) {
            throw new ServiceException(404, "报告不存在");
        }
        // BR-4.10-26 / C-4.10-04：未归档硬阻断 + 违规留痕 + 通知数据治理专员
        if (!BiSlaReport.ST_ARCHIVED.equals(r.getStatus())) {
            auditLogWriter.write("bi-sla:" + p, "BI_SLA_PUBLISH_BLOCKED", r.getStatus(), "BLOCKED",
                    "未归档发布被阻断（BR-4.10-26/C-4.10-04）");
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_DATA_GOV', 'BiSlaPublishBlocked', ?, ?, ?, "
                            + "'0', 'system', NOW(), '0', 0)",
                    uuid(), r.getId(), "违规尝试：SLA 报告未归档发布",
                    "报告 " + p + "（状态 " + r.getStatus() + "）被尝试未归档对外发布，已阻断。");
            throw new ServiceException(422, "报告未归档，不可发布");
        }
        // 归档后发布：水印 PDF + 渠道推送（站内信真实，邮件/企微模拟——偏差见 proposal）+ 记录
        String pdfRef = writePdf(r);
        String operator = operator();
        jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, BIZ_TYPE, BIZ_ID, TITLE, "
                        + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, 'BiSlaPublished', ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                uuid(), operator, r.getId(),
                "月度报表 SLA 报告已发布：" + p, "报告已归档并发布，PDF 已生成（含水印）。");
        List<Map<String, Object>> publishRec = parse(r.getPublishJson());
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("channel", "NOTICE");
        rec.put("at", LocalDateTime.now().format(TS));
        rec.put("operator", operator);
        rec.put("pdfRef", pdfRef);
        publishRec.add(rec);
        r.setPublishBy(operator);
        r.setPublishAt(LocalDateTime.now());
        r.setPublishJson(toJson(publishRec));
        r.setStatus(BiSlaReport.ST_PUBLISHED);
        r.setUpdateBy(operator);
        reportDao.updateById(r);
        auditLogWriter.write("bi-sla:" + p, "BI_SLA_REPORT_PUBLISHED", "ARCHIVED", "PUBLISHED",
                "归档后对外发布（PDF+站内信）");
        return Map.of("id", r.getId(), "period", p, "status", r.getStatus(), "pdfRef", pdfRef);
    }

    @Override
    public Map<String, Object> pdf(String period) {
        BiSlaReport r = latest(normalizePeriod(period));
        if (r == null) {
            throw new ServiceException(404, "报告不存在");
        }
        if (!BiSlaReport.ST_ARCHIVED.equals(r.getStatus())
                && !BiSlaReport.ST_PUBLISHED.equals(r.getStatus())) {
            throw new ServiceException(422, "报告未归档，不可下载发布版 PDF");
        }
        return Map.of("period", r.getPeriod(), "pdfRef", writePdf(r));
    }

    /** 水印 PDF（用户名|时间戳|流水号；内容=概览表+三要素+改进） */
    private String writePdf(BiSlaReport r) {
        try {
            List<String> headers = List.of("指标", "当月值", "目标", "单位", "状态", "备注");
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> m : parse(r.getMetricsJson())) {
                List<Object> row = new ArrayList<>();
                row.add(str(m.get("metricKey")));
                row.add(m.get("metricValue") == null ? "-" : String.valueOf(m.get("metricValue")));
                row.add(m.get("target") == null ? "-" : String.valueOf(m.get("target")));
                row.add(str(m.get("unit")));
                row.add(statusText(str(m.get("status"))));
                row.add("Y".equals(str(m.get("partialFlag"))) ? "部分数据" : "");
                rows.add(row);
            }
            rows.add(0, List.of("月度报表 SLA 报告 " + r.getPeriod() + "（版本 V" + r.getVersion() + "）",
                    "", "", "", "", ""));
            String watermark = operator() + "|" + LocalDateTime.now().format(TS) + "|" + r.getId();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            pdfWriter.write(headers, rows, watermark, bos);
            Path dir = Path.of("./data/bi-sla-report");
            Files.createDirectories(dir);
            Path f = dir.resolve(r.getPeriod() + "-V" + r.getVersion() + ".pdf");
            Files.write(f, bos.toByteArray());
            return f.toString();
        } catch (Exception e) {
            throw new ServiceException(422, "PDF 生成失败：" + e.getMessage());
        }
    }

    private String statusText(String st) {
        if ("ACHIEVED".equals(st)) {
            return "达标";
        }
        if ("WARN".equals(st)) {
            return "预警";
        }
        if ("BELOW".equals(st)) {
            return "未达标";
        }
        if ("INFO".equals(st)) {
            return "参考";
        }
        return st == null ? "-" : st;
    }

    // ================= 调度 =================

    @Override
    public void monthlyCollect() {
        String last = YearMonth.now().minusMonths(1).format(PERIOD_T);
        try {
            Map<String, Object> r = collectMetrics(last);
            log.info("bi sla monthly collect done: {}", r);
        } catch (Exception e) {
            log.error("bi sla monthly collect failed: {}", e.getMessage(), e);
        }
    }

    // ================= 工具 =================

    private BiSlaReport latest(String period) {
        List<BiSlaReport> list = reportDao.selectList(new QueryWrapper<BiSlaReport>()
                .eq("PERIOD", period).eq("DEL_FLAG", "0").orderByDesc("VERSION"));
        return list.isEmpty() ? null : list.get(0);
    }

    private BiSlaReport draftOf(String period) {
        BiSlaReport r = latest(period);
        if (r == null) {
            throw new ServiceException(404, "报告不存在，请先生成");
        }
        // 归档锁定（D4）：ARCHIVED/PUBLISHED 拒编辑
        if (BiSlaReport.ST_ARCHIVED.equals(r.getStatus())
                || BiSlaReport.ST_PUBLISHED.equals(r.getStatus())) {
            throw new ServiceException(422, "报告已归档（版本锁定），修改请走新版本");
        }
        if (BiSlaReport.ST_PENDING.equals(r.getStatus())) {
            throw new ServiceException(422, "报告审核中，不可编辑");
        }
        return r;
    }

    private String normalizePeriod(String p) {
        try {
            return YearMonth.parse(p).format(PERIOD_T);
        } catch (Exception e) {
            throw new ServiceException(422, "月份格式须为 yyyy-MM");
        }
    }

    private LocalDate monthStart(String period) {
        return YearMonth.parse(period).atDay(1);
    }

    private LocalDate monthEnd(String period) {
        return YearMonth.parse(period).atEndOfMonth();
    }

    private List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object v) {
        try {
            return JSON.writeValueAsString(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "JSON 序列化失败");
        }
    }

    private BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String operator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
