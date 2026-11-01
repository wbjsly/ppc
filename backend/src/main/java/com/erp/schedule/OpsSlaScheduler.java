package com.erp.schedule;

import com.erp.service.ops.EscalationService;
import com.erp.service.ops.OpsCapacityService;
import com.erp.service.ops.OpsSyncDelayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 运维 SLA 调度（add-ops-sla design D4）。
 * 错峰：容量 03:40 / 延迟 04:00 / 报告次月 1 日 06:00（避开 03:30 BI SLA）/
 * 升级扫描每 5 分钟。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsSlaScheduler {

    private final EscalationService escalationService;
    private final OpsSyncDelayService syncDelayService;
    private final OpsCapacityService capacityService;
    private final com.erp.service.ops.OpsSlaReportService slaReportService;

    /** 升级扫描每 5 分钟（BR-5.8-02/03/15） */
    @Scheduled(cron = "0 */5 * * * ?")
    public void escalationScan() {
        try {
            int n = escalationService.scanAndEscalate();
            if (n > 0) {
                log.info("ops escalation scan handled {}", n);
            }
        } catch (Exception e) {
            log.warn("ops escalation scan failed: {}", e.getMessage());
        }
    }

    /** 同步延迟采集每日 04:00（BR-5.8-11） */
    @Scheduled(cron = "0 0 4 * * ?")
    public void syncDelayCollect() {
        try {
            syncDelayService.collect();
        } catch (Exception e) {
            log.warn("ops sync delay collect failed: {}", e.getMessage());
        }
    }

    /** 容量采集每日 03:40（BR-5.8-10） */
    @Scheduled(cron = "0 40 3 * * ?")
    public void capacityCollect() {
        try {
            capacityService.collect();
        } catch (Exception e) {
            log.warn("ops capacity collect failed: {}", e.getMessage());
        }
    }

    /** 月度 SLA 报告次月 1 日 06:00（BR-5.8-07） */
    @Scheduled(cron = "0 0 6 1 * ?")
    public void monthlyReport() {
        try {
            String period = YearMonth.now().minusMonths(1).toString();
            slaReportService.generate(period);
            log.info("ops sla report generated for {}", period);
        } catch (Exception e) {
            log.warn("ops sla monthly report failed: {}", e.getMessage());
        }
    }
}
