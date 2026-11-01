package com.erp.schedule;

import com.erp.service.bi.DailyReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 日报推送调度（add-daily-report-subscription，spec bi-daily-report，design D3/D4）。
 * 07:00 推送引擎（就绪等待→差异化生成→三通道推送）；08:00 未阅读扫描（30 天 L4 提示）。
 * 错峰：02:00 KPI 快照 / 02:40 导出清理 / 03:00 临时报表生命周期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportScheduler {

    private final DailyReportService dailyReportService;

    /** 每日 07:00 推送（业务日 = T-1 数据日） */
    @Scheduled(cron = "0 0 7 * * ?")
    public void dailyPush() {
        LocalDate pushDate = LocalDate.now().minusDays(1);
        try {
            log.info("daily report push start: pushDate={}", pushDate);
            var result = dailyReportService.pushAll(pushDate);
            log.info("daily report push done: {}", result);
        } catch (Exception e) {
            log.error("daily report push failed: {}", e.getMessage(), e);
        }
    }

    /** 每日 08:00 连续未阅读扫描（L4 提示 30 天去重） */
    @Scheduled(cron = "0 0 8 * * ?")
    public void unreadScan() {
        try {
            int n = dailyReportService.scanUnreadAndPrompt();
            log.info("daily report unread scan done: prompted={}", n);
        } catch (Exception e) {
            log.error("daily report unread scan failed: {}", e.getMessage(), e);
        }
    }
}
