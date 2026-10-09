package com.erp.schedule;

import com.erp.service.inv.StockSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 库存恒等式校验调度（spec stock-snapshot，FR-4.4-2-5 / BR-4.4-15；design D6）。
 * 每日 01:30 逐行断言 QTY = AVAILABLE + QC + FIN；差异写报告并通知仓库主管。
 * 逻辑在 StockSnapshotService.runCheckReport（按运行日幂等，重跑安全）。
 * 单实例部署假设（沿 17 个既有调度器），无分布式锁。
 */
@Slf4j
@Component
public class StockCheckScheduler {

    private final StockSnapshotService stockSnapshotService;

    public StockCheckScheduler(StockSnapshotService stockSnapshotService) {
        this.stockSnapshotService = stockSnapshotService;
    }

    /** 每日 01:30（日结 00:05 之后错峰，两任务无数据依赖） */
    @Scheduled(cron = "0 30 1 * * ?")
    public void dailyCheck() {
        try {
            stockSnapshotService.runCheckReport();
        } catch (Exception e) {
            log.warn("stock identity check failed (will retry next day): {}", e.getMessage());
        }
    }
}
