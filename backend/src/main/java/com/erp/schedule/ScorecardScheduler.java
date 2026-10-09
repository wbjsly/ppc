package com.erp.schedule;

import com.erp.service.scm.ScorecardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/** 记分卡月度采集（spec supplier-scorecard FR-4.9-7-2：每月 1 日 02:00 自动）。 */
@Slf4j
@Component
public class ScorecardScheduler {

    private final ScorecardService scorecardService;

    public ScorecardScheduler(ScorecardService scorecardService) {
        this.scorecardService = scorecardService;
    }

    @Scheduled(cron = "0 0 2 1 * *")
    public void collect() {
        try {
            String month = YearMonth.now().minusMonths(1).toString().replace("-", "");
            var r = scorecardService.collectMonth(month);
            log.info("scorecard collect done: {}", r);
        } catch (Exception e) {
            log.warn("scorecard collect failed: {}", e.getMessage());
        }
    }
}
