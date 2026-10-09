package com.erp.schedule;

import com.erp.service.qms.CapaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CAPA Critical 遏制倒计时扫描（tasks 9.1，BR-4.12-32）：
 * 24h 未确认遏制 → CONTAIN_ESCALATED=1 升级质量总监并纳入质量月报未达标项（幂等）。
 */
@Slf4j
@Component
public class CapaContainScheduler {

    private final CapaService capaService;

    public CapaContainScheduler(CapaService capaService) {
        this.capaService = capaService;
    }

    /** 每 10 分钟扫描（可由 app.qms.capa-sweep-ms 覆盖） */
    @Scheduled(fixedDelayString = "${app.qms.capa-sweep-ms:600000}")
    public void sweepContainment() {
        try {
            int n = capaService.sweepContainment();
            if (n > 0) {
                log.info("CAPA containment escalation sweep: handled={}", n);
            }
        } catch (Exception e) {
            log.warn("CAPA containment sweep failed (retry next tick): {}", e.getMessage());
        }
    }
}
