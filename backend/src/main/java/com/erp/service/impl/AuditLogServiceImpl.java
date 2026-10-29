package com.erp.service.impl;

import com.erp.service.AuditLogService;
import com.erp.service.MaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogServiceImpl implements AuditLogService {

    private final JdbcTemplate jdbc;
    private final MaskService maskService;

    // 注意：search SQL 已将列别名为驼峰（oldValue/newValue），REASON 无别名
    private static final List<String> MASK_FIELDS = List.of("oldValue", "newValue", "REASON");

    @Override
    public Map<String, Object> search(String changeBy, String fieldName, String empId,
                                      String keyword, String from, String to,
                                      Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 || size > 500 ? 50 : size;
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, EMP_ID AS empId, FIELD_NAME AS fieldName, OLD_VALUE AS oldValue, "
                        + "NEW_VALUE AS newValue, REASON AS reason, CHANGE_BY AS changeBy, "
                        + "CHANGE_AT AS changeAt, SOURCE_IP AS sourceIp, TERMINAL AS terminal "
                        + "FROM hr_change_log WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(changeBy)) {
            sql.append("AND CHANGE_BY LIKE ? ");
            args.add("%" + changeBy + "%");
        }
        if (StringUtils.hasText(fieldName)) {
            sql.append("AND FIELD_NAME LIKE ? ");
            args.add("%" + fieldName + "%");
        }
        if (StringUtils.hasText(empId)) {
            sql.append("AND EMP_ID LIKE ? ");
            args.add("%" + empId + "%");
        }
        if (StringUtils.hasText(keyword)) {
            sql.append("AND (OLD_VALUE LIKE ? OR NEW_VALUE LIKE ? OR REASON LIKE ? OR FIELD_NAME LIKE ?) ");
            String kw = "%" + keyword + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        if (StringUtils.hasText(from)) {
            sql.append("AND CHANGE_AT >= ? ");
            args.add(from);
        }
        if (StringUtils.hasText(to)) {
            sql.append("AND CHANGE_AT <= ? ");
            args.add(to);
        }
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + sql + ") t", Integer.class,
                args.toArray());
        sql.append("ORDER BY CHANGE_AT DESC, ID DESC LIMIT ? OFFSET ? ");
        args.add(s);
        args.add((p - 1) * s);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        // 脱敏响应映射（D6：检索展示与取证 CSV 同一 MaskService 口径）
        for (Map<String, Object> row : rows) {
            for (String f : MASK_FIELDS) {
                Object v = row.get(f);
                if (v instanceof String s0) {
                    row.put(f, maskService.applyAll(s0));
                }
            }
            // REASON 为 MySQL 保留字倾向，JDBC 别名被保持大写键 → 规整为小写（前端/冒烟按 reason 取）
            if (row.containsKey("REASON")) {
                row.put("reason", row.remove("REASON"));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows);
        return out;
    }

    @Override
    public List<Map<String, Object>> tamperList(Integer limit) {
        int l = limit == null || limit < 1 || limit > 500 ? 100 : limit;
        return jdbc.queryForList(
                "SELECT ID AS id, ACTION_TYPE AS actionType, EMP_ID AS empId, FIELD_NAME AS fieldName, "
                        + "CHANGE_BY AS changeBy, BYPASS AS bypass, ATTEMPT_AT AS attemptAt "
                        + "FROM erp_ops_audit_tamper ORDER BY ATTEMPT_AT DESC LIMIT ?", l);
    }

    @Override
    public Map<String, Object> sweepTamperNotices() {
        // 未通知的尝试行（以 erp_sys_notice BIZ_ID=尝试行 ID 去重）→ 逐行通知内审角色持有者
        List<Map<String, Object>> fresh = jdbc.queryForList(
                "SELECT t.ID, t.ACTION_TYPE, t.EMP_ID, t.FIELD_NAME, t.CHANGE_BY, t.BYPASS, t.ATTEMPT_AT "
                        + "FROM erp_ops_audit_tamper t "
                        + "WHERE NOT EXISTS (SELECT 1 FROM erp_sys_notice n "
                        + "WHERE n.BIZ_TYPE='AuditTamper' AND n.BIZ_ID=t.ID AND n.DEL_FLAG='0') "
                        + "ORDER BY t.ATTEMPT_AT DESC LIMIT 100");
        int notified = 0;
        for (Map<String, Object> t : fresh) {
            List<String> holders = jdbc.queryForList(
                    "SELECT u.ID FROM erp_admin_user u JOIN erp_admin_user_role ur ON ur.USER_ID=u.ID "
                            + "JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                            + "WHERE r.ROLE_CODE='ROLE_UAR_AUDITOR' AND u.STATUS='1' AND u.DEL_FLAG='0'",
                    String.class);
            String title = "⚠ 审计日志删改尝试告警（BR-5.6-13）";
            boolean bypass = "1".equals(String.valueOf(t.get("BYPASS")));
            String content = String.format("检测到审计日志 %s 尝试（%s）：对象 %s / 动作 %s，操作者 %s，时间 %s",
                    t.get("ACTION_TYPE"), bypass ? "豁免放行" : "已拒绝",
                    t.get("EMP_ID"), t.get("FIELD_NAME"), t.get("CHANGE_BY"), t.get("ATTEMPT_AT"));
            for (String uid : holders) {
                try {
                    jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, "
                                    + "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                    + "VALUES (?,?,?,?,?,?, '0', 'audit-tamper-sweep', NOW(), '0', 0)",
                            UUID.randomUUID().toString().replace("-", ""), uid, title, content,
                            "AuditTamper", String.valueOf(t.get("ID")));
                    notified++;
                } catch (Exception e) {
                    log.warn("tamper notice write failed: {}", e.getMessage());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fresh", fresh.size());
        out.put("notified", notified);
        return out;
    }
}
