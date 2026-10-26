package com.erp.schedule;

import com.erp.service.pm.PmAllocService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 间接成本月度分摊任务（add-cost-collection-freeze，每月 1 日 02:30；FR-4.8-5-5）。
 * 分摊对象 = 上个自然月（period = 上月 yyyy-MM）；同 PERIOD+POOL 幂等防重。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmAllocMonthlyTask {

    private final PmAllocService allocService;

    @Scheduled(cron = "0 30 2 1 * ?")
    public void run() {
        try {
            String period = LocalDate.now().minusMonths(1).toString().substring(0, 7);
            Map<String, Object> out = allocService.runPeriod(period);
            log.info("pm alloc monthly: period={} pools={} details={} skipped={}",
                    out.get("period"), out.get("poolCount"),
                    out.get("allocatedDetails"), out.get("skippedPools"));
        } catch (Exception e) {
            log.error("pm alloc monthly failed", e);
        }
    }
}
