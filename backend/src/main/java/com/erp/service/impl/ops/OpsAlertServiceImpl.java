package com.erp.service.impl.ops;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.ops.OpsAlertDao;
import com.erp.entity.ops.OpsAlert;
import com.erp.service.ops.OpsAlertService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运维告警实现（add-ops-sla，BR-5.8-08/09 风暴聚合与 P1 豁免）。
 * 推送沿 NoticeChannelSender 模式（站内信真实落 erp_sys_notice，TARGET_ROLE 通道；
 * 偏差声明：无电话网关，P1 最高优先级以 TITLE 前缀标注）。
 */
@Slf4j
@Service
public class OpsAlertServiceImpl implements OpsAlertService {

    private final OpsAlertDao alertDao;
    private final JdbcTemplate jdbc;

    @Value("${ops.alert.storm-threshold:200}")
    private int stormThreshold;

    public OpsAlertServiceImpl(OpsAlertDao alertDao, JdbcTemplate jdbc) {
        this.alertDao = alertDao;
        this.jdbc = jdbc;
    }

    @Override
    public String raise(String level, String source, String title, String detail,
                        BigDecimal metricValue, BigDecimal threshold, String eventType) {
        OpsAlert a = new OpsAlert();
        a.setAlertNo("AL-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 900 + 100));
        a.setLevel(level);
        a.setSource(source);
        a.setEventType(eventType);
        a.setTitle(title);
        a.setDetail(detail);
        a.setMetricValue(metricValue);
        a.setThreshold(threshold);
        a.setStatus(OpsAlert.ST_OPEN);
        a.setEscalationLevel(0);
        a.setSuppressed("0");
        a.setCreateBy("system");
        a.setCreateDate(LocalDateTime.now());

        // 风暴判定：滚动 1 分钟同来源计数（不含本条）> 阈值 → 聚合根因 + 抑制
        Integer recent = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_ops_alert WHERE SOURCE=? AND DEL_FLAG='0' "
                        + "AND CREATE_DATE >= DATE_SUB(NOW(), INTERVAL 1 MINUTE)",
                Integer.class, source);
        int cnt = recent == null ? 0 : recent;
        boolean storm = cnt >= stormThreshold;

        // P1 豁免（BR-5.8-09）：P1 不被聚合，立即推送
        boolean exempt = OpsAlert.LV_P1.equals(level);
        boolean becomesRoot = false;
        if (storm && !exempt) {
            String window = "SW-" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
            OpsAlert root = findRootCause(source, window);
            if (root != null) {
                // 后续告警挂靠既有根因并被抑制
                a.setSuppressed("1");
                a.setSuppressWindow(window);
                a.setRootCauseId(root.getId());
                a.setStatus(OpsAlert.ST_SUPPRESSED);
            } else {
                // 首条风暴告警自身成为聚合根因（不抑制，作为根因事件推送）
                a.setSuppressWindow(window);
                becomesRoot = true;
            }
        }
        alertDao.insert(a);

        // 推送：P1 立即（最高优先级标注）；非风暴普通推送；风暴首条以根因事件推一条（含影响面摘要）
        if (exempt || !storm) {
            pushNotice(a);
        } else if (becomesRoot) {
            pushNoticeRoot(a);
        }
        return a.getId();
    }

    /** 找同窗口同来源的根因（无 ROOT_CAUSE_ID 且带抑制窗口的首条） */
    private OpsAlert findRootCause(String source, String window) {
        List<OpsAlert> roots = alertDao.selectList(new LambdaQueryWrapper<OpsAlert>()
                .eq(OpsAlert::getSource, source)
                .eq(OpsAlert::getSuppressWindow, window)
                .isNull(OpsAlert::getRootCauseId)
                .last("LIMIT 1"));
        return roots.isEmpty() ? null : roots.get(0);
    }

    private void pushNoticeRoot(OpsAlert a) {
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_ADMIN', 'OpsAlertStorm', ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                    UUID.randomUUID().toString().replace("-", ""), a.getId(),
                    "【告警风暴·聚合根因】" + a.getTitle(),
                    (a.getDetail() == null ? "" : a.getDetail())
                            + "（来源 " + a.getSource() + "，原始告警已聚合暂存，详见告警归档）");
        } catch (Exception e) {
            log.warn("ops storm root notice failed (non-blocking): {}", e.getMessage());
        }
    }

    private void pushNotice(OpsAlert a) {
        try {
            String prefix = OpsAlert.LV_P1.equals(a.getLevel()) ? "【P1 立即】" : "";
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, BIZ_TYPE, BIZ_ID, TITLE, "
                            + "CONTENT, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'ROLE_ADMIN', 'OpsAlert', ?, ?, ?, '0', 'system', NOW(), '0', 0)",
                    UUID.randomUUID().toString().replace("-", ""), a.getId(),
                    prefix + a.getTitle(),
                    (a.getDetail() == null ? "" : a.getDetail())
                            + "（级别 " + a.getLevel() + "，来源 " + a.getSource() + "）");
        } catch (Exception e) {
            log.warn("ops alert notice push failed (non-blocking): {}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> list(String level, String source, String status, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT ID AS id, ALERT_NO AS alertNo, LEVEL AS level, "
                + "SOURCE AS source, EVENT_TYPE AS eventType, TITLE AS title, DETAIL AS detail, "
                + "METRIC_VALUE AS metricValue, THRESHOLD AS threshold, ROOT_CAUSE_ID AS rootCauseId, "
                + "SUPPRESSED AS suppressed, STATUS AS status, ESCALATION_LEVEL AS escalationLevel, "
                + "HANDLER AS handler, CREATE_DATE AS createDate FROM erp_ops_alert WHERE DEL_FLAG='0'");
        List<Object> args = new ArrayList<>();
        if (level != null && !level.isBlank()) {
            sql.append(" AND LEVEL=?");
            args.add(level.trim());
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND SOURCE=?");
            args.add(source.trim());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND STATUS=?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY CREATE_DATE DESC LIMIT ? OFFSET ?");
        int lim = Math.min(Math.max(size, 1), 100);
        args.add(lim);
        args.add(Math.max(page - 1, 0) * lim);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    public void claim(String alertId, String handler) {
        OpsAlert a = alertDao.selectById(alertId);
        if (a == null) {
            throw new ServiceException(404, "告警不存在：" + alertId);
        }
        if (!OpsAlert.ST_OPEN.equals(a.getStatus())) {
            throw new ServiceException(422, "仅 OPEN 状态可认领（当前 " + a.getStatus() + "）");
        }
        a.setStatus(OpsAlert.ST_CLAIMED);
        a.setHandler(handler);
        a.setUpdateBy(handler);
        a.setUpdateDate(LocalDateTime.now());
        alertDao.updateById(a);
    }

    @Override
    public void resolve(String alertId, String note) {
        OpsAlert a = alertDao.selectById(alertId);
        if (a == null) {
            throw new ServiceException(404, "告警不存在：" + alertId);
        }
        a.setStatus(OpsAlert.ST_RESOLVED);
        a.setUpdateBy(operator());
        a.setUpdateDate(LocalDateTime.now());
        if (note != null && !note.isBlank()) {
            a.setDetail((a.getDetail() == null ? "" : a.getDetail() + " | ") + "处置：" + note);
        }
        alertDao.updateById(a);
    }

    @Override
    public List<Map<String, Object>> stormRootCauses(int limit) {
        return jdbc.queryForList("SELECT ID AS id, ALERT_NO AS alertNo, TITLE AS title, "
                        + "SOURCE AS source, DETAIL AS detail, CREATE_DATE AS createDate, "
                        + "(SELECT COUNT(*) FROM erp_ops_alert c WHERE c.ROOT_CAUSE_ID = a.ID "
                        + "  AND c.DEL_FLAG='0') AS affectedCnt "
                        + "FROM erp_ops_alert a WHERE a.DEL_FLAG='0' AND a.ROOT_CAUSE_ID IS NULL "
                        + "AND a.SUPPRESS_WINDOW IS NOT NULL ORDER BY a.CREATE_DATE DESC LIMIT ?",
                Math.min(Math.max(limit, 1), 50));
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
