package com.erp.schedule;

import com.erp.service.proc.ReturnService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退货补发/退款跟踪扫描（tasks 8.4）：出库起算 30 天未闭环 → OVERDUE 标记 + 提醒日志（幂等）。
 * 单实例部署假设（本地 Docker），无分布式锁。
 */
@Slf4j
@Component
public class ReturnTrackScheduler {

    private final ReturnService returnService;

    public ReturnTrackScheduler(ReturnService returnService) {
        this.returnService = returnService;
    }

    /** 每小时扫描（可由 app.proc.return-track-sweep-ms 覆盖） */
    @Scheduled(fixedDelayString = "${app.proc.return-track-sweep-ms:3600000}")
    public void sweepTrack() {
        try {
            int n = returnService.sweepTrackOverdue();
            if (n > 0) {
                log.info("return track overdue sweep: handled={}", n);
            }
        } catch (Exception e) {
            log.warn("return track sweep failed (retry next tick): {}", e.getMessage());
        }
    }
}
