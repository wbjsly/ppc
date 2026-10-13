package com.erp.schedule;

import com.erp.service.qms.InspectionLotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 检验任务调度（change rework-inspection-tasks，spec inspection-lot 超时扫描与升级通知）。
 * 5min 扫描：在途批超时 → OVERDUE_FLAG 落库 + 通知质检员/车间主管 + 一次自动改派。
 * 巡检计划触发仍由 {@link PatrolPlanScheduler} 承担（职责分离）。
 * 单实例部署假设（本地 Docker），无分布式锁；失败不阻断下一轮。
 */
@Slf4j
@Component
public class InspectionTaskScheduler {

    private final InspectionLotService lotService;

    public InspectionTaskScheduler(InspectionLotService lotService) {
        this.lotService = lotService;
    }

    @Scheduled(fixedDelayString = "${app.qms.inspection-sweep-ms:300000}")
    public void sweepOverdue() {
        try {
            int n = lotService.sweepOverdue();
            if (n > 0) {
                log.info("inspection overdue sweep done: lots={}", n);
            }
        } catch (Exception e) {
            log.warn("inspection overdue sweep failed (retry next tick): {}", e.getMessage());
        }
    }
}
