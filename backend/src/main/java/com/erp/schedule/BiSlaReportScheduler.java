package com.erp.schedule;

import com.erp.service.bi.BiSlaReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 报表 SLA 月度采集调度（add-bi-sla-report design D2）。
 * 次月 1 日 03:30 采集上月五指标（错开 02:00 KPI 快照/02:40 导出清理/03:00 临时报表/07:00 日报）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiSlaReportScheduler {

    private final BiSlaReportService biSlaReportService;

    /** 次月 1 日 03:30（cron 日=1） */
    @Scheduled(cron = "0 30 3 1 * ?")
    public void monthlyCollect() {
        biSlaReportService.monthlyCollect();
    }
}
