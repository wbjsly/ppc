package com.erp.schedule;

import com.erp.service.bi.ExtractService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * BI 抽取调度（spec bi-data-pipeline 时间窗口 01:00-05:00）。
 * cron 01~04 点整点推进（含失败重试），05 点后不启动；PAUSED 等人工确认端点。
 */
@Slf4j
@Component
public class BiExtractScheduler {

    private final ExtractService extractService;

    public BiExtractScheduler(ExtractService extractService) {
        this.extractService = extractService;
    }

    @Scheduled(cron = "0 0 1-4 * * *")
    public void run() {
        try {
            var result = extractService.runBatch(LocalDate.now(), false);
            if (Integer.parseInt(String.valueOf(result.getOrDefault("executed", 0))) > 0) {
                log.info("bi extract batch done: {}", result);
            }
        } catch (Exception e) {
            log.warn("bi extract batch failed: {}", e.getMessage());
        }
    }
}
