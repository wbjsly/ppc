package com.erp.schedule;

import com.erp.service.qms.GaugeService;
import com.erp.service.qms.ScarService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SCAR 回复超期 + 器具到期预警扫描（tasks 10.3 / 10.6）：
 *  - SCAR 超 scar-reply-days → 升采购经理 + 每 3 天提醒 + 响应及时性扣分标记（幂等）
 *  - 器具 NEXT_CAL_DATE 30/7 天内 → 预警清单（可查）
 * 单实例部署假设（本地 Docker），无分布式锁。
 */
@Slf4j
@Component
public class QmsExtendedSweepScheduler {

    private final ScarService scarService;
    private final GaugeService gaugeService;

    public QmsExtendedSweepScheduler(ScarService scarService, GaugeService gaugeService) {
        this.scarService = scarService;
        this.gaugeService = gaugeService;
    }

    /** 每 30 分钟扫描（可由 app.qms.extended-sweep-ms 覆盖） */
    @Scheduled(fixedDelayString = "${app.qms.extended-sweep-ms:1800000}")
    public void sweep() {
        try {
            int over = scarService.sweepReplyOverdue();
            if (over > 0) {
                log.info("SCAR reply overdue sweep: handled={}", over);
            }
        } catch (Exception e) {
            log.warn("SCAR reply sweep failed (retry next tick): {}", e.getMessage());
        }
        try {
            gaugeService.sweepDue();
        } catch (Exception e) {
            log.warn("gauge due sweep failed (retry next tick): {}", e.getMessage());
        }
    }
}
