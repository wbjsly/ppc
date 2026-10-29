package com.erp.service.impl;

import com.erp.service.GrantLedgerService;
import com.erp.service.GrantSweepService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 到期回收与提醒扫描实现（design D6）。reclaim 经 GrantLedgerService 代理调用
 * （逐笔独立事务，单笔失败不回滚整批）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrantSweepServiceImpl implements GrantSweepService {

    private final JdbcTemplate jdbc;
    private final GrantLedgerService grantLedgerService;
    private final com.erp.service.BreakGlassService breakGlassService;

    @Override
    public Map<String, Object> sweep(LocalDateTime now) {
        int reclaimed = 0;
        int reminded = 0;
        int failed = 0;

        // 1) 到期回收（EXPIRE_AT <= now）
        List<String> due = jdbc.queryForList(
                "SELECT ID FROM erp_admin_grant_ledger WHERE STATUS='ACTIVE' "
                        + "AND EXPIRE_AT <= ? AND DEL_FLAG='0'", String.class, now);
        for (String id : due) {
            try {
                grantLedgerService.reclaim(id, "grant-sweep");
                reclaimed++;
                // BG 行补账（add-break-glass-console D3/D4）：申请单回写 RECLAIMED + 事后审计生成
                // （reclaimLedger 内部 reclaim 幂等，仅补回写与审计）
                String src = jdbc.queryForObject(
                        "SELECT SOURCE FROM erp_admin_grant_ledger WHERE ID=?",
                        String.class, id);
                if ("BREAK_GLASS".equals(src)) {
                    breakGlassService.reclaimLedger(id);
                }
            } catch (Exception e) {
                failed++;
                log.warn("reclaim failed for {}: {}", id, e.getMessage());
            }
        }

        // 2) 到期前 7 日提醒（REMIND_AT 幂等，只提醒未到期行）
        List<Map<String, Object>> upcoming = jdbc.queryForList(
                "SELECT ID, TARGET_USER_ID, ROLE_CODE, EXPIRE_AT, APPLY_ID "
                        + "FROM erp_admin_grant_ledger WHERE STATUS='ACTIVE' AND REMIND_AT IS NULL "
                        + "AND EXPIRE_AT > ? AND EXPIRE_AT <= ? AND DEL_FLAG='0'",
                now, now.plusDays(7));
        for (Map<String, Object> row : upcoming) {
            try {
                jdbc.update("UPDATE erp_admin_grant_ledger SET REMIND_AT=?, UPDATE_DATE=NOW() WHERE ID=?",
                        now, row.get("ID"));
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, "
                                + "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?,?,?,?,?,?, '0', ?, NOW(), '0', 0)",
                        UUID.randomUUID().toString().replace("-", ""), row.get("TARGET_USER_ID"),
                        "权限到期提醒",
                        "角色 " + row.get("ROLE_CODE") + " 将于 " + row.get("EXPIRE_AT")
                                + " 到期（申请单 " + row.get("APPLY_ID")
                                + "）。续期须重新提交权限申请并走完整审批链，到期未续期将自动回收。",
                        "GrantLedger", String.valueOf(row.get("ID")), "grant-sweep");
                reminded++;
            } catch (Exception e) {
                failed++;
                log.warn("remind failed for {}: {}", row.get("ID"), e.getMessage());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reclaimed", reclaimed);
        out.put("reminded", reminded);
        out.put("failed", failed);
        return out;
    }
}
