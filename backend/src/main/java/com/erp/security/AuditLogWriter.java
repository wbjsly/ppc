package com.erp.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 审计写入门面（add-audit-log-console D3）：新代码（取证下载留痕等）经此写入 hr_change_log，
 * 自动带 SOURCE_IP/TERMINAL（取自 AuditContext；无请求上下文时为空）。
 * 既有 10+ 处 jdbc 直插点不改（渐进覆盖，偏差表已记）。
 */
@Component
@RequiredArgsConstructor
public class AuditLogWriter {

    private final JdbcTemplate jdbc;

    /** 写入一条审计（empId=对象，fieldName=动作，old/new=前后值，reason=说明） */
    public void write(String empId, String fieldName, String oldValue, String newValue, String reason) {
        String operator;
        try {
            operator = com.erp.util.SecurityUtils.getCurrentUserId();
        } catch (Exception e) {
            operator = null;
        }
        // CHANGE_BY NOT NULL：无请求上下文（定时任务/测试直调）兜底
        if (operator == null || operator.isBlank()) {
            operator = "system";
        }
        jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, REASON, "
                        + "CHANGE_BY, CHANGE_AT, SOURCE_IP, TERMINAL) VALUES (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString().replace("-", ""),
                empId, fieldName, oldValue, newValue, reason,
                operator, LocalDateTime.now(),
                AuditContext.getIp(), AuditContext.getTerminal());
    }
}
