package com.erp.schedule;

import com.erp.service.bi.KpiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 经营看板调度（add-kpi-dashboard design D3）：
 * 每日 02:00 快照任务（错开 02:40 导出清理、03:00 临时报表生命周期），
 * 内含预警扫描；失败 >0 时站内通知 BI_DESIGNER。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KpiScheduler {

    private final KpiService kpiService;
    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 2 * * ?")
    public void dailySnapshot() {
        try {
            Map<String, Object> result = kpiService.runSnapshot();
            log.info("kpi daily snapshot: {}", result);
            Object failed = result.get("failed");
            if (failed instanceof Number n && n.intValue() > 0) {
                jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_ROLE, TITLE, CONTENT, "
                                + "BIZ_TYPE, BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                                + "VALUES (?,?,?,?,? ,?,?,?,NOW(),'0',0)",
                        UUID.randomUUID().toString().replace("-", ""), "ROLE_BI_DESIGNER",
                        "KPI 快照失败告警", "今日 KPI 快照有 " + n + " 项失败，详见经营看板 14.7.1",
                        "KpiSnapshotFail", String.valueOf(result.get("snapDate")), "0", "kpi-scheduler");
            }
        } catch (Exception e) {
            log.warn("kpi daily snapshot error: {}", e.getMessage());
        }
    }
}
