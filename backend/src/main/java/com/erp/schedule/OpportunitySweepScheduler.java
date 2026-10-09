package com.erp.schedule;

import com.erp.service.crm.OpportunityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 商机调度（spec opportunity-management FR-4.8-1-6）：
 * 同一阶段停留 >30 天 → 标记 STAGE_OVERDUE（列表 L4 提示，页面实时计算兜底）。
 * 单实例部署假设（本地 Docker），无分布式锁。
 */
@Slf4j
@Component
public class OpportunitySweepScheduler {

    private final OpportunityService opportunityService;

    public OpportunitySweepScheduler(OpportunityService opportunityService) {
        this.opportunityService = opportunityService;
    }

    /** 每日 08:10 扫描（在线索清扫之后） */
    @Scheduled(cron = "0 10 8 * * ?")
    public void sweepOpportunities() {
        try {
            int n = opportunityService.sweepOverdue();
            if (n > 0) {
                log.info("opportunity stage overdue sweep done: {} rows", n);
            }
        } catch (Exception e) {
            log.warn("opportunity sweep failed (will retry next tick): {}", e.getMessage());
        }
    }
}
