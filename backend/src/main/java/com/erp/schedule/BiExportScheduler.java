package com.erp.schedule;

import com.erp.service.bi.ExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台导出 worker（add-export-console design D5）：
 * 10s 队列消费（优先级 SMALL→NORMAL→LOW）；5 分钟超时终止（RUNNING>30min）；
 * 每日 03:20 生命周期（7 天到期删密文 + 24h 删除前提醒）。
 */
@Slf4j
@Component
public class BiExportScheduler {

    private final ExportService exportService;

    public BiExportScheduler(ExportService exportService) {
        this.exportService = exportService;
    }

    @Scheduled(fixedDelay = 10000)
    public void drain() {
        try {
            int n = exportService.drainOnce();
            if (n > 0) {
                log.info("bi export worker processed {}", n);
            }
        } catch (Exception e) {
            log.warn("bi export worker failed: {}", e.getMessage());
        }
    }

    /** 超时终止（FR-4.10-4-3：RUNNING >30 分钟自动终止） */
    @Scheduled(fixedDelay = 300000)
    public void sweepTimeouts() {
        try {
            int n = exportService.sweepTimeouts();
            if (n > 0) {
                log.warn("bi export timeout terminated {}", n);
            }
        } catch (Exception e) {
            log.warn("bi export timeout sweep failed: {}", e.getMessage());
        }
    }

    /** 生命周期（FR-4.10-4-6：每日 03:20 清理到期文件 + 24h 删除前提醒） */
    @Scheduled(cron = "0 20 3 * * *")
    public void sweepLifecycle() {
        try {
            int[] r = exportService.sweepLifecycle();
            if (r[0] > 0 || r[1] > 0) {
                log.info("bi export lifecycle: cleaned={} reminded={}", r[0], r[1]);
            }
        } catch (Exception e) {
            log.warn("bi export lifecycle sweep failed: {}", e.getMessage());
        }
    }
}
