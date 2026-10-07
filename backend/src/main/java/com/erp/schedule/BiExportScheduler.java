package com.erp.schedule;

import com.erp.service.bi.ExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 后台导出 worker（design D9：每 10 秒扫描队列）。 */
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
}
