package com.erp.service.impl;

import com.erp.service.ActivationSweepService;
import com.erp.service.GrantLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指定时点生效扫描实现（design D5）。经代理调用 GrantLedgerService.activate
 * （保证每笔独立事务回滚语义）；单笔失败记录并继续。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivationSweepServiceImpl implements ActivationSweepService {

    private final JdbcTemplate jdbc;
    private final GrantLedgerService grantLedgerService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Map<String, Object> sweepDue(LocalDateTime now) {
        // 两类到点单：SCHEDULED 到时点；IMMEDIATE 特权"待窗口"单（窗口开后重试）
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT ID, TARGET_USER_ID, ROLES_JSON, KIND, VALID_MONTHS "
                        + "FROM erp_admin_perm_request "
                        + "WHERE STATUS = 'APPROVED_PENDING' AND DEL_FLAG = '0' "
                        + "AND ((ACTIVATION_MODE = 'SCHEDULED' AND ACTIVATE_AT IS NOT NULL "
                        + "AND ACTIVATE_AT <= ?) OR (ACTIVATION_MODE = 'IMMEDIATE'))",
                now);
        int activated = 0;
        int failed = 0;
        for (Map<String, Object> row : due) {
            try {
                String applyId = String.valueOf(row.get("ID"));
                @SuppressWarnings("unchecked")
                List<String> roles = MAPPER.readValue(String.valueOf(row.get("ROLES_JSON")),
                        MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
                int months = row.get("VALID_MONTHS") == null ? 12
                        : Integer.parseInt(String.valueOf(row.get("VALID_MONTHS")));
                boolean privileged = roles.stream().anyMatch(
                        r -> com.erp.service.system.PrivilegedRoles.contains(r));
                grantLedgerService.activate(applyId, String.valueOf(row.get("TARGET_USER_ID")),
                        roles, "EXCEPTION".equals(row.get("KIND")) ? "EXCEPTION" : "NORMAL",
                        now.plusMonths(months), privileged, "activation-sweep");
                activated++;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("变更窗口")) {
                    continue; // 仍待窗口，非失败
                }
                failed++;
                log.warn("activation sweep failed for {}: {}", row.get("ID"), e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activated", activated);
        out.put("failed", failed);
        return out;
    }
}
