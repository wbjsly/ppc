package com.erp.service.impl;

import com.erp.common.ServiceException;
import com.erp.security.AuditLogWriter;
import com.erp.service.AuthzIncidentService;
import com.erp.service.system.AccountLifecycleService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 越权事件实现（design D1 合表 / D4 幂等锚=活动行 / D6 处置三动作 / D7 恢复）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthzIncidentServiceImpl implements AuthzIncidentService {

    private final JdbcTemplate jdbc;
    private final AuditLogWriter auditLogWriter;
    private final AccountLifecycleService accountLifecycleService;

    private static final String ST_ACTIVE = "ACTIVE";
    private static final String ST_RESTORED = "RESTORED";
    private static final String ST_DISABLED = "DISABLED";

    // ---------------- 触发（D4） ----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean trigger(String userId, int hitCount, int threshold) {
        // 幂等锚：账号已有 ACTIVE 事件行 → 不重复打标/告警/审计
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_authz_incident WHERE TARGET_USER_ID=? AND STATUS=? AND DEL_FLAG='0'",
                Integer.class, userId, ST_ACTIVE);
        if (active != null && active > 0) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        String id = uuid();
        jdbc.update("INSERT INTO erp_authz_incident (ID, TARGET_USER_ID, HIT_COUNT, THRESHOLD, "
                        + "TRIGGERED_AT, EXPIRE_AT, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?,?,?,?,?,?,?, ?, NOW(), '0', 0)",
                id, userId, hitCount, threshold, now, now.plusDays(7), ST_ACTIVE, operator());
        // 双角色站内告警（决策甲：ADMIN + 内审）
        String title = "⚠ 疑似越权探测告警（BR-5.6-11）";
        String content = String.format(
                "账号 %s 在 5 分钟窗口内 403 拒绝达 %d 次（阈值 %d），已自动临时降权 7 天"
                        + "（敏感操作将被拒绝）。请尽快联系本人核实处置。",
                userId, hitCount, threshold);
        for (String role : List.of("ROLE_ADMIN", "ROLE_UAR_AUDITOR")) {
            for (String uid : roleHolders(role)) {
                try {
                    jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, "
                                    + "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                    + "VALUES (?,?,?,?,?,?, '0', 'authz-sweep', NOW(), '0', 0)",
                            uuid(), uid, title, content, "AuthzAlert", id);
                } catch (Exception e) {
                    log.warn("authz alert notice failed: {}", e.getMessage());
                }
            }
        }
        // SECURITY_EVENT 审计（16.7 门面写入带 IP，供 16.7 检索）
        auditLogWriter.write(userId, "SECURITY_EVENT", null,
                "AUTHZ_BURST hits=" + hitCount + " threshold=" + threshold,
                "疑似越权探测触发临时降权（事件 " + id + "，到期 " + now.plusDays(7) + "）");
        return true;
    }

    @Override
    public boolean hasActiveRestriction(String userId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_authz_incident WHERE TARGET_USER_ID=? AND STATUS=? AND DEL_FLAG='0'",
                Integer.class, userId, ST_ACTIVE);
        return cnt != null && cnt > 0;
    }

    // ---------------- 查询（D8 限 ADMIN） ----------------

    @Override
    public Map<String, Object> list(String status, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 || size > 500 ? 50 : size;
        StringBuilder sql = new StringBuilder(
                "SELECT i.ID AS id, i.TARGET_USER_ID AS targetUserId, u.USERNAME AS targetUsername, "
                        + "i.HIT_COUNT AS hitCount, i.THRESHOLD AS threshold, "
                        + "i.TRIGGERED_AT AS triggeredAt, i.EXPIRE_AT AS expireAt, i.STATUS AS status, "
                        + "i.DISPOSITION AS disposition, i.CONCLUSION AS conclusion, "
                        + "i.TRAINING_FLAG AS trainingFlag, i.DISPOSITION_BY AS dispositionBy, "
                        + "i.DISPOSITION_AT AS dispositionAt, i.RESTORE_BY AS restoreBy, "
                        + "i.RESTORE_AT AS restoreAt, i.RESTORE_REASON AS restoreReason, "
                        + "i.CREATE_DATE AS createDate "
                        + "FROM erp_authz_incident i "
                        + "LEFT JOIN erp_admin_user u ON u.ID = i.TARGET_USER_ID "
                        + "WHERE i.DEL_FLAG = '0' ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(status)) {
            sql.append("AND i.STATUS = ? ");
            args.add(status);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + sql + ") t",
                Integer.class, args.toArray());
        sql.append("ORDER BY i.TRIGGERED_AT DESC LIMIT ? OFFSET ? ");
        args.add(s);
        args.add((p - 1) * s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", jdbc.queryForList(sql.toString(), args.toArray()));
        return out;
    }

    // ---------------- 处置三动作（D6） ----------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispose(String id, String action, String conclusion, String trainingFlag) {
        Map<String, Object> inc = requireIncident(id);
        if (inc.get("DISPOSITION") != null) {
            throw new ServiceException(422, "告警已处置，不可重复处置");
        }
        if (!StringUtils.hasText(conclusion) || conclusion.trim().length() < 10) {
            throw new ServiceException(422, "处置结论必填且不少于 10 字（须写明核实过程）");
        }
        boolean misuse = "MISUSE".equals(action);
        boolean restore = "RESTORE".equals(action);
        boolean malicious = "MALICIOUS".equals(action);
        if (!misuse && !restore && !malicious) {
            throw new ServiceException(422, "处置动作仅支持 MISUSE/RESTORE/MALICIOUS");
        }
        String me = operator();
        LocalDateTime now = LocalDateTime.now();

        if (malicious) {
            // 联动 16.1 强停（STATUS='0'，不用归档 '2'——调查可能为误判，保留恢复余地）
            accountLifecycleService.disable(String.valueOf(inc.get("TARGET_USER_ID")), me);
            jdbc.update("UPDATE erp_authz_incident SET DISPOSITION=?, CONCLUSION=?, TRAINING_FLAG=?, "
                            + "DISPOSITION_BY=?, DISPOSITION_AT=?, STATUS=?, UPDATE_DATE=NOW() WHERE ID=?",
                    action, conclusion.trim(), normalizeFlag(trainingFlag), me, now, ST_DISABLED, id);
        } else if (restore) {
            jdbc.update("UPDATE erp_authz_incident SET DISPOSITION=?, CONCLUSION=?, TRAINING_FLAG=?, "
                            + "DISPOSITION_BY=?, DISPOSITION_AT=?, EXPIRE_AT=?, UPDATE_DATE=NOW() WHERE ID=?",
                    action, conclusion.trim(), normalizeFlag(trainingFlag), me, now, now, id);
            restoreRow(id, "手动提前恢复（处置 RESTORE）", me);
        } else {
            // MISUSE：保持 ACTIVE 至到期自动恢复
            jdbc.update("UPDATE erp_authz_incident SET DISPOSITION=?, CONCLUSION=?, TRAINING_FLAG=?, "
                            + "DISPOSITION_BY=?, DISPOSITION_AT=?, UPDATE_DATE=NOW() WHERE ID=?",
                    action, conclusion.trim(), normalizeFlag(trainingFlag), me, now, id);
        }
        auditLogWriter.write(String.valueOf(inc.get("TARGET_USER_ID")), "SECURITY_EVENT",
                null, "DISPOSITION=" + action,
                "越权事件处置（事件 " + id + "）：" + conclusion.trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreNow(String id, String reason) {
        Map<String, Object> inc = requireIncident(id);
        if (!ST_ACTIVE.equals(inc.get("STATUS"))) {
            // 幂等：非 ACTIVE 跳过
            return;
        }
        restoreRow(id, StringUtils.hasText(reason) ? "手动提前恢复：" + reason : "手动提前恢复",
                operator());
        auditLogWriter.write(String.valueOf(inc.get("TARGET_USER_ID")), "SECURITY_EVENT",
                null, "RESTORE_MANUAL", "降权提前恢复（事件 " + id + "）：" + reason);
    }

    @Override
    public Map<String, Object> restoreExpired() {
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT ID, TARGET_USER_ID FROM erp_authz_incident "
                        + "WHERE STATUS=? AND DEL_FLAG='0' AND EXPIRE_AT <= NOW()", ST_ACTIVE);
        int restored = 0;
        for (Map<String, Object> d : due) {
            try {
                restoreRow(String.valueOf(d.get("ID")), "到期自动恢复", "system");
                auditLogWriter.write(String.valueOf(d.get("TARGET_USER_ID")), "SECURITY_EVENT",
                        null, "RESTORE_AUTO", "降权到期自动恢复（事件 " + d.get("ID") + "）");
                restored++;
            } catch (Exception e) {
                log.warn("authz restore failed for {}: {}", d.get("ID"), e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restored", restored);
        return out;
    }

    // ---------------- 内部 ----------------

    private void restoreRow(String id, String reason, String by) {
        jdbc.update("UPDATE erp_authz_incident SET STATUS=?, RESTORE_BY=?, RESTORE_AT=NOW(), "
                        + "RESTORE_REASON=?, UPDATE_DATE=NOW() WHERE ID=? AND STATUS=?",
                ST_RESTORED, by, reason, id, ST_ACTIVE);
    }

    private Map<String, Object> requireIncident(String id) {
        Map<String, Object> inc = jdbc.queryForList(
                "SELECT ID, TARGET_USER_ID, STATUS, DISPOSITION FROM erp_authz_incident "
                        + "WHERE ID=? AND DEL_FLAG='0'", id).stream().findFirst().orElse(null);
        if (inc == null) {
            throw new ServiceException(404, "越权事件不存在：" + id);
        }
        return inc;
    }

    private List<String> roleHolders(String roleCode) {
        return jdbc.queryForList(
                "SELECT u.ID FROM erp_admin_user u JOIN erp_admin_user_role ur ON ur.USER_ID = u.ID "
                        + "JOIN erp_admin_role r ON r.ID = ur.ROLE_ID "
                        + "WHERE r.ROLE_CODE = ? AND u.STATUS = '1' AND u.DEL_FLAG='0'",
                String.class, roleCode);
    }

    private String normalizeFlag(String trainingFlag) {
        return "1".equals(trainingFlag) ? "1" : "0";
    }

    private String operator() {
        String me = SecurityUtils.getCurrentUserId();
        return me == null ? "authz-sweep" : me;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
