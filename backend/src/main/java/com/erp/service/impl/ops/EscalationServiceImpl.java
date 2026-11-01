package com.erp.service.impl.ops;

import com.erp.dao.ops.OpsAlertDao;
import com.erp.dao.ops.OpsEscalationLogDao;
import com.erp.dao.ops.OpsOncallDao;
import com.erp.entity.ops.OpsAlert;
import com.erp.entity.ops.OpsEscalationLog;
import com.erp.entity.ops.OpsOncall;
import com.erp.service.ops.EscalationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 升级引擎实现（add-ops-sla，BR-5.8-02/03/15）。
 * 升级动作 = HANDLER 强制改派 + 升级日志 + 最高优先级站内信
 * （偏差声明：无电话网关，电话外呼以站内信+改派+日志替代）。
 */
@Slf4j
@Service
public class EscalationServiceImpl implements EscalationService {

    private final OpsAlertDao alertDao;
    private final OpsOncallDao oncallDao;
    private final OpsEscalationLogDao logDao;
    private final JdbcTemplate jdbc;

    @Value("${ops.escalation.p1-response-min:15}")
    private int p1ResponseMin;

    @Value("${ops.escalation.level-response-min:30}")
    private int levelResponseMin;

    public EscalationServiceImpl(OpsAlertDao alertDao, OpsOncallDao oncallDao,
                                 OpsEscalationLogDao logDao, JdbcTemplate jdbc) {
        this.alertDao = alertDao;
        this.oncallDao = oncallDao;
        this.logDao = logDao;
        this.jdbc = jdbc;
    }

    @Override
    public int scanAndEscalate() {
        int handled = 0;
        handled += escalateOpenAlerts();
        handled += escalateOverdueReviews();
        return handled;
    }

    /** ① OPEN 告警超时升级 */
    private int escalateOpenAlerts() {
        int n = 0;
        List<OpsAlert> open = alertDao.selectList(new LambdaQueryWrapper<OpsAlert>()
                .eq(OpsAlert::getStatus, OpsAlert.ST_OPEN)
                .eq(OpsAlert::getSuppressed, "0")
                .isNull(OpsAlert::getRootCauseId)
                .last("LIMIT 100"));
        for (OpsAlert a : open) {
            try {
                if (escalateOne(a)) {
                    n++;
                }
            } catch (Exception e) {
                log.warn("escalation scan failed for {}: {}", a.getId(), e.getMessage());
            }
        }
        return n;
    }

    private boolean escalateOne(OpsAlert a) {
        LocalDateTime created = a.getCreateDate() == null ? LocalDateTime.now() : a.getCreateDate();
        int waitedMin = (int) java.time.Duration.between(created, LocalDateTime.now()).toMinutes();
        boolean p1 = OpsAlert.LV_P1.equals(a.getLevel());
        // P1：超过 P1_RESPONSE_MIN 未认领 → 升级下一值班人（BR-5.8-02）
        // 非 P1：超过 ESCALATION_RESPONSE_MIN 未认领 → 按层级升级（BR-5.8-03）
        int limit = p1 ? p1ResponseMin : levelResponseMin;
        if (waitedMin < limit) {
            return false;
        }
        int cur = a.getEscalationLevel() == null ? 0 : a.getEscalationLevel();
        if (cur >= 3) {
            return false; // 已到 L3 顶，不再升级
        }
        int next = cur + 1;
        String nextLevel = "L" + next;
        String nextHandler = oncallOf(LocalDate.now(), nextLevel);
        String from = cur == 0 ? null : "L" + cur;

        // 改派 + 标注已升级
        a.setStatus(OpsAlert.ST_ESCALATED);
        a.setEscalationLevel(next);
        if (nextHandler != null && !nextHandler.isBlank()) {
            a.setHandler(nextHandler);
        }
        a.setUpdateBy("escalation-engine");
        a.setUpdateDate(LocalDateTime.now());
        alertDao.updateById(a);

        // 升级日志
        OpsEscalationLog l = new OpsEscalationLog();
        l.setAlertId(a.getId());
        l.setFromLevel(from);
        l.setToLevel(nextLevel);
        l.setReason(p1 ? "P1 超 " + p1ResponseMin + " 分钟未认领（BR-5.8-02）"
                : "层级超 " + levelResponseMin + " 分钟无处置（BR-5.8-03）");
        l.setNotifyChannel("NOTICE");
        l.setEscalateAt(LocalDateTime.now());
        l.setCreateBy("escalation-engine");
        logDao.insert(l);

        // 最高优先级站内信（P1 外呼替代，含抄送上级语义：TARGET_ROLE 通知）
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_ADMIN', 'OpsEscalation', ?, ?, ?, "
                            + "'0', 'escalation-engine', NOW(), '0', 0)",
                    UUID.randomUUID().toString().replace("-", ""), a.getId(),
                    "【升级·" + nextLevel + "】" + a.getTitle(),
                    "告警 " + a.getAlertNo() + " 已升级至 " + nextLevel
                            + (nextHandler == null ? "" : "，值班人 " + nextHandler)
                            + "；原因：" + l.getReason() + "（原层级待办保留并标注已升级）");
        } catch (Exception e) {
            log.warn("escalation notice failed (non-blocking): {}", e.getMessage());
        }
        return true;
    }

    /** ② 复盘逾期：P1/P2 工单关闭超 5 工作日 REPORT_STATUS 未生成 → 升级（BR-5.8-15） */
    private int escalateOverdueReviews() {
        int n = 0;
        List<Map<String, Object>> overdue = jdbc.queryForList(
                "SELECT ID, INC_NO, TITLE FROM erp_ops_incident "
                        + "WHERE DEL_FLAG='0' AND ALERT_LEVEL IN ('P1','P2') "
                        + "AND STATUS IN ('CLOSED','RESOLVED') "
                        + "AND (REPORT_STATUS IS NULL OR REPORT_STATUS='NOT_GENERATED') "
                        + "AND UPDATE_DATE < DATE_SUB(CURDATE(), INTERVAL 5 DAY) "
                        + "AND (OVERDUE_ESCALATED IS NULL OR OVERDUE_ESCALATED='0') "
                        + "LIMIT 50");
        for (Map<String, Object> row : overdue) {
            String id = String.valueOf(row.get("ID"));
            jdbc.update("UPDATE erp_ops_incident SET OVERDUE_ESCALATED='1', UPDATE_DATE=NOW() WHERE ID=?", id);
            OpsEscalationLog l = new OpsEscalationLog();
            l.setIncidentId(id);
            l.setToLevel("OPS-LEAD");
            l.setReason("复盘报告逾期 5 个工作日未生成（BR-5.8-15）");
            l.setNotifyChannel("NOTICE");
            l.setEscalateAt(LocalDateTime.now());
            l.setCreateBy("escalation-engine");
            logDao.insert(l);
            try {
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                                + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?, 'ROLE_ADMIN', 'OpsReviewOverdue', ?, ?, ?, "
                                + "'0', 'escalation-engine', NOW(), '0', 0)",
                        UUID.randomUUID().toString().replace("-", ""), id,
                        "【复盘逾期】" + row.get("TITLE"),
                        "工单 " + row.get("INC_NO") + " 关闭超 5 个工作日复盘报告仍未生成，已升级运维负责人并抄送架构委员会。");
            } catch (Exception e) {
                log.warn("review overdue notice failed: {}", e.getMessage());
            }
            n++;
        }
        return n;
    }

    @Override
    public List<Map<String, Object>> escalationLogs(String alertId, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, ALERT_ID AS alertId, INCIDENT_ID AS incidentId, "
                        + "FROM_LEVEL AS fromLevel, TO_LEVEL AS toLevel, REASON AS reason, "
                        + "NOTIFY_CHANNEL AS notifyChannel, ESCALATE_AT AS escalateAt "
                        + "FROM erp_ops_escalation_log WHERE DEL_FLAG='0'");
        List<Object> args = new ArrayList<>();
        if (alertId != null && !alertId.isBlank()) {
            sql.append(" AND ALERT_ID=?");
            args.add(alertId.trim());
        }
        sql.append(" ORDER BY ESCALATE_AT DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 200));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    public String oncallOf(LocalDate date, String level) {
        OpsOncall o = oncallDao.selectOne(new LambdaQueryWrapper<OpsOncall>()
                .eq(OpsOncall::getDutyDate, date)
                .eq(OpsOncall::getOnLevel, level)
                .last("LIMIT 1"));
        return o == null ? null : o.getUserName();
    }
}
