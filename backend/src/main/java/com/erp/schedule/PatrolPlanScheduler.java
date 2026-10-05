package com.erp.schedule;

import com.erp.service.qms.InspectionLotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * IPQC 巡检计划调度（task 5.10，偏差 D5）：每分钟扫描到点计划并生成 IPQC 检验批。
 * 单实例部署假设（本地 Docker），无分布式锁；计划内失败不阻断其余计划。
 */
@Slf4j
@Component
public class PatrolPlanScheduler {

    private final InspectionLotService lotService;

    public PatrolPlanScheduler(InspectionLotService lotService) {
        this.lotService = lotService;
    }

    @Scheduled(fixedDelayString = "${app.qms.patrol-sweep-ms:60000}")
    public void runPatrolPlans() {
        try {
            int n = lotService.runPatrolPlans();
            if (n > 0) {
                log.info("patrol plan sweep done: lots={}", n);
            }
        } catch (Exception e) {
            log.warn("patrol plan sweep failed (retry next tick): {}", e.getMessage());
        }
    }
}
