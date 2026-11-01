package com.erp.service.impl.ops;

import com.erp.common.ServiceException;
import com.erp.service.ops.OpsSlaReportService;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运维 SLA 月度报告实现（add-ops-sla，BR-5.8-07/16）。
 * 报告快照存 erp_ops_incident 旁路 JSON 于专用行？——采用独立轻量存储：
 * 快照落 erp_sys_param 不合适，直接用 incident 表 IS_IMPROVE 承载改进项，
 * 报告主体以 JSON 存 erp_ops_capacity_metric 不合适——新建最小内嵌：
 * 报告快照存 erp_ops_alert 旁不合适 → 用 jdbc 直存新表会超迁移范围，
 * 故以「参数表快照键」承载（OPS_SLA_REPORT_<period>），归档/发布状态存 JSON 内。
 * （设计取舍：报告是一月一行的不可变快照，参数表 KEY 唯一性正好承载幂等）
 */
@Slf4j
@Service
public class OpsSlaReportServiceImpl implements OpsSlaReportService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int IMPROVE_DAYS = 30;

    private final JdbcTemplate jdbc;

    public OpsSlaReportServiceImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generate(String period) {
        requirePeriod(period);
        Map<String, Object> metrics = new LinkedHashMap<>();

        // ① 变更成功率 = CLOSED 非回滚 / CLOSED 总数
        Map<String, Object> ch = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN STATUS='CLOSED' THEN 1 ELSE 0 END),0) AS closed, "
                        + "COALESCE(SUM(CASE WHEN STATUS='CLOSED' AND ROLLBACK_REASON IS NULL "
                        + "  THEN 1 ELSE 0 END),0) AS clean "
                        + "FROM erp_ops_change_ticket WHERE DEL_FLAG='0' "
                        + "AND CREATE_DATE >= ? AND CREATE_DATE < DATE_ADD(?, INTERVAL 1 MONTH)",
                period + "-01", period + "-01");
        metrics.put("changeTotal", ch.get("total"));
        metrics.put("changeCloseRate", rate(ch.get("clean"), ch.get("closed")));

        // ② 告警响应率 = 已认领/处置 / 总告警（非抑制非根因）
        Map<String, Object> al = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN STATUS IN ('CLAIMED','ESCALATED','RESOLVED') "
                        + "  THEN 1 ELSE 0 END),0) AS responded "
                        + "FROM erp_ops_alert WHERE DEL_FLAG='0' AND SUPPRESSED='0' "
                        + "AND CREATE_DATE >= ? AND CREATE_DATE < DATE_ADD(?, INTERVAL 1 MONTH)",
                period + "-01", period + "-01");
        metrics.put("alertTotal", al.get("total"));
        metrics.put("alertResponseRate", rate(al.get("responded"), al.get("total")));

        // ③ 升级及时率 = 有升级日志的告警中 30 分钟内升级占比
        Map<String, Object> es = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN TIMESTAMPDIFF(MINUTE, a.CREATE_DATE, l.ESCALATE_AT) "
                        + "  <= 30 THEN 1 ELSE 0 END),0) AS timely "
                        + "FROM erp_ops_escalation_log l JOIN erp_ops_alert a ON a.ID=l.ALERT_ID "
                        + "WHERE l.DEL_FLAG='0' AND l.ESCALATE_AT >= ? AND l.ESCALATE_AT < DATE_ADD(?, INTERVAL 1 MONTH)",
                period + "-01", period + "-01");
        metrics.put("escalationTotal", es.get("total"));
        metrics.put("escalationTimelyRate", rate(es.get("timely"), es.get("total")));

        // ④ 容量水位 = 月末三指标均值
        Map<String, Object> cap = jdbc.queryForMap(
                "SELECT COALESCE(AVG(METRIC_VALUE),0) AS avg_util FROM erp_ops_capacity_metric "
                        + "WHERE DEL_FLAG='0' AND METRIC_DATE >= ? AND METRIC_DATE < DATE_ADD(?, INTERVAL 1 MONTH)",
                period + "-01", period + "-01");
        metrics.put("capacityAvgUtil", cap.get("avg_util"));

        // ⑤ 同步延迟达成率 = 告警中 sync-delay 未超阈占比（超阈即 raise 了告警，以告警量反推）
        Map<String, Object> sd = jdbc.queryForMap(
                "SELECT COALESCE(SUM(CASE WHEN SOURCE='sync-delay' THEN 1 ELSE 0 END),0) AS delayedCnt "
                        + "FROM erp_ops_alert WHERE DEL_FLAG='0' "
                        + "AND CREATE_DATE >= ? AND CREATE_DATE < DATE_ADD(?, INTERVAL 1 MONTH)",
                period + "-01", period + "-01");
        metrics.put("syncDelayAlerts", sd.get("delayedCnt"));

        // 目标与达标判定（目标：变更≥95/告警响应≥95/升级及时≥90/容量≤80/延迟告警=0）
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("changeCloseRate", "变更关闭无回滚率", dec(metrics.get("changeCloseRate")), 95));
        items.add(item("alertResponseRate", "告警响应率", dec(metrics.get("alertResponseRate")), 95));
        items.add(item("escalationTimelyRate", "升级及时率", dec(metrics.get("escalationTimelyRate")), 90));
        items.add(item("capacityAvgUtil", "容量平均水位(反向)", dec(metrics.get("capacityAvgUtil")), 80, true));
        items.add(item("syncDelay", "同步延迟告警数(反向)", dec(metrics.get("syncDelayAlerts")), 0, true));
        metrics.put("items", items);

        // 未达标 → 自动生成改进项（BR-5.8-07，30 天时限）
        List<String> improveIds = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (!Boolean.TRUE.equals(it.get("met"))) {
                String incId = createImprovement(period, it);
                improveIds.add(incId);
            }
        }
        metrics.put("improvementIds", improveIds);

        // 快照落参数表（幂等键 OPS_SLA_REPORT_<period>）
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("period", period);
        snap.put("status", "DRAFT");
        snap.put("metrics", metrics);
        snap.put("generatedAt", LocalDateTime.now().toString());
        String json;
        try {
            json = JSON.writeValueAsString(snap);
        } catch (Exception e) {
            throw new ServiceException(500, "报告序列化失败：" + e.getMessage());
        }
        upsertSnapshot(period, json);
        return snap;
    }

    private String createImprovement(String period, Map<String, Object> it) {
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_ops_incident (ID, INC_NO, TITLE, STATUS, HANDLER, "
                        + "ROOT_CAUSE, IMPROVE_ACTION, IMPROVE_OWNER, IMPROVE_DUE_DATE, "
                        + "IS_IMPROVE, REPORT_STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, 'OPEN', ?, ?, NULL, ?, DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                        + "'1', 'NOT_GENERATED', 'sla-report', NOW(), '0', 0)",
                id, "IMP-" + period.replace("-", "") + "-" + (int) (Math.random() * 900 + 100),
                "SLA 未达标改进：" + it.get("name"), operator(),
                it.get("name") + " 未达标（实际 " + it.get("actual") + "，目标 " + it.get("target") + "）",
                operator());
        return id;
    }

    @Override
    public Map<String, Object> report(String period) {
        requirePeriod(period);
        String json = snapshotJson(period);
        if (json == null) {
            throw new ServiceException(404, "报告不存在：" + period + "（请先生成）");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = JSON.readValue(json, Map.class);
            return snap;
        } catch (Exception e) {
            throw new ServiceException(500, "报告解析失败：" + e.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(String period) {
        Map<String, Object> snap = report(period);
        if (!"DRAFT".equals(snap.get("status"))) {
            throw new ServiceException(422, "仅 DRAFT 可归档（当前 " + snap.get("status") + "）");
        }
        snap.put("status", "ARCHIVED");
        snap.put("archivedAt", LocalDateTime.now().toString());
        upsertSnapshot(period, toJson(snap));
        return snap;
    }

    @Override
    public Map<String, Object> publish(String period, String scope) {
        Map<String, Object> snap = report(period);
        // BR-5.8-16 未归档发布硬阻断（C-0-01），尝试留痕
        if (!"ARCHIVED".equals(snap.get("status"))) {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (UUID(), 'ROLE_ADMIN', 'OpsSlaPublishBlocked', ?, ?, ?, "
                            + "'0', ?, NOW(), '0', 0)",
                    period, "SLA 报告未归档发布被阻断（BR-5.8-16）",
                    "报告 " + period + " 状态 " + snap.get("status") + "，发布尝试已留痕。", operator());
            throw new ServiceException(422, "报告未归档，不可发布（BR-5.8-16）");
        }
        snap.put("status", "PUBLISHED");
        snap.put("publishScope", scope == null ? "ALL" : scope);
        snap.put("publishedAt", LocalDateTime.now().toString());
        upsertSnapshot(period, toJson(snap));
        return snap;
    }

    @Override
    public List<Map<String, Object>> improvements(String status, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, INC_NO AS incNo, TITLE AS title, ROOT_CAUSE AS rootCause, "
                        + "IMPROVE_ACTION AS improveAction, IMPROVE_OWNER AS improveOwner, "
                        + "IMPROVE_DUE_DATE AS improveDueDate, STATUS AS status, "
                        + "OVERDUE_ESCALATED AS overdueEscalated "
                        + "FROM erp_ops_incident WHERE DEL_FLAG='0' AND IS_IMPROVE='1'");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND STATUS=?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY CREATE_DATE DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 200));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------------- 工具 ----------------

    private Object rate(Object num, Object den) {
        BigDecimal n = dec(num);
        BigDecimal d = dec(den);
        if (n == null || d == null || d.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.valueOf(100); // 无样本视为达标
        }
        return n.multiply(BigDecimal.valueOf(100)).divide(d, 2, RoundingMode.HALF_UP);
    }

    private Map<String, Object> item(String key, String name, BigDecimal actual, BigDecimal target) {
        return item(key, name, actual, target, false);
    }

    private Map<String, Object> item(String key, String name, BigDecimal actual, BigDecimal target,
                                     boolean reverse) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("actual", actual);
        m.put("target", target);
        boolean met = reverse
                ? actual.compareTo(target) <= 0
                : actual.compareTo(target) >= 0;
        m.put("met", met);
        return m;
    }

    private Map<String, Object> item(String key, String name, BigDecimal actual, int target) {
        return item(key, name, actual, BigDecimal.valueOf(target), false);
    }

    private Map<String, Object> item(String key, String name, BigDecimal actual, int target,
                                     boolean reverse) {
        return item(key, name, actual, BigDecimal.valueOf(target), reverse);
    }

    private String toJson(Map<String, Object> snap) {
        try {
            return JSON.writeValueAsString(snap);
        } catch (Exception e) {
            throw new ServiceException(500, "报告序列化失败：" + e.getMessage());
        }
    }

    private void upsertSnapshot(String period, String json) {
        Map<String, Object> snap = readSnapRow(period);
        String status = snap == null ? "DRAFT" : str(snap.get("STATUS"));
        String scope = snap == null ? null : str(snap.get("PUBLISH_SCOPE"));
        // 从 json 中提取状态/范围保持一致
        try {
            JsonNode n = JSON.readTree(json);
            status = n.path("status").asText("DRAFT");
            scope = n.has("publishScope") ? n.path("publishScope").asText(null) : scope;
        } catch (Exception ignored) {
            // 解析失败用旧状态
        }
        if (snap == null) {
            jdbc.update("INSERT INTO erp_ops_sla_report (ID, PERIOD, STATUS, METRICS_JSON, "
                            + "PUBLISH_SCOPE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (UUID(), ?, ?, ?, ?, 'sla-report', NOW(), '0', 0)",
                    period, status, json, scope);
        } else {
            jdbc.update("UPDATE erp_ops_sla_report SET STATUS=?, METRICS_JSON=?, PUBLISH_SCOPE=?, "
                            + "UPDATE_BY='sla-report', UPDATE_DATE=NOW() WHERE PERIOD=?",
                    status, json, scope, period);
        }
    }

    private String snapshotJson(String period) {
        try {
            return jdbc.queryForObject(
                    "SELECT METRICS_JSON FROM erp_ops_sla_report WHERE PERIOD=? AND DEL_FLAG='0'",
                    String.class, period);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> readSnapRow(String period) {
        try {
            return jdbc.queryForMap(
                    "SELECT STATUS, PUBLISH_SCOPE FROM erp_ops_sla_report WHERE PERIOD=? AND DEL_FLAG='0'",
                    period);
        } catch (Exception e) {
            return null;
        }
    }

    private void requirePeriod(String period) {
        if (period == null || !period.matches("\\d{4}-\\d{2}")) {
            throw new ServiceException(422, "period 须为 yyyy-MM");
        }
    }

    private BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
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

    private String operator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
