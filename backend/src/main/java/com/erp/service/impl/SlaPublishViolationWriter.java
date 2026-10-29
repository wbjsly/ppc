package com.erp.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * SLA 发布违规留痕（add-sla-report-console design D4，BR-5.6-16 / C-5.6-05）。
 * REQUIRES_NEW 独立事务：违规审计与通知必须在 publish() 抛 422 回滚主事务后仍然落库
 * （沿 16.3 ViolationAuditWriter 同一模式）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaPublishViolationWriter {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPublishViolation(String reportId, String status, String creator,
                                       String operator) {
        log.warn("SLA publish violation: reportId={} status={} operator={}", reportId, status, operator);
        // 违规尝试审计（SECURITY_EVENT，16.7 可检索）
        jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, REASON, "
                        + "CHANGE_BY, CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                uuid(), reportId, "SECURITY_EVENT", status, "PUBLISH_BLOCKED",
                "SLA 报告未归档即尝试对外发布（BR-5.6-16，状态 " + status + "），已阻断",
                operator, LocalDateTime.now());
        // 通知报告责任人（生成人）与内审（C-5.6-05）
        String title = "⚠ SLA 报告发布违规尝试告警（BR-5.6-16）";
        String content = "报告 " + reportId + "（状态 " + status + "）被 " + operator
                + " 尝试未归档对外发布，已硬阻断并留痕。请核查发布操作合规性。";
        List<String> targets = new java.util.ArrayList<>();
        if (creator != null && !creator.isBlank()) {
            targets.add(creator);
        }
        targets.addAll(jdbc.queryForList(
                "SELECT u.ID FROM erp_admin_user u JOIN erp_admin_user_role ur ON ur.USER_ID=u.ID "
                        + "JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                        + "WHERE r.ROLE_CODE='ROLE_UAR_AUDITOR' AND u.STATUS='1' AND u.DEL_FLAG='0'",
                String.class));
        for (String uid : targets) {
            try {
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, "
                                + "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?,?,?,?,?,?, '0', 'sla-violation', NOW(), '0', 0)",
                        uuid(), uid, title, content, "SlaPublishViolation", reportId);
            } catch (Exception e) {
                log.warn("sla violation notice write failed: {}", e.getMessage());
            }
        }
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
