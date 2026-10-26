package com.erp.schedule;

import com.erp.service.pm.PmCostVarianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 成本偏差每日扫描（add-cost-collection-freeze，03:40；FR-4.8-5-6 / BR-4.8-21）。
 * 双阈值：偏差率 > COST_TOLERANCE 仅预警；实际累计 > 基线 → 自动冻结。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmCostDailyTask {

    private final PmCostVarianceService varianceService;

    @Scheduled(cron = "0 40 3 * * ?")
    public void run() {
        try {
            Map<String, Object> scan = varianceService.dailyScan();
            log.info("pm cost daily scan: frozen={} yellow={} red={} skipped={}",
                    scan.get("frozen"), scan.get("yellow"), scan.get("red"), scan.get("skipped"));
        } catch (Exception e) {
            log.error("pm cost variance scan failed", e);
        }
    }
}
