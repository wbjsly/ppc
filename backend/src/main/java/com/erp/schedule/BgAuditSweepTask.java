package com.erp.schedule;

import com.erp.service.BreakGlassService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Break-Glass 事后审计逾期告警扫描（add-break-glass-console spec BR-5.6-08）：
 * 回收后 24h 未签署 → 每 12h 站内提醒安全管理员直至签署。
 * enabled 默认 false（app.admin.bg-audit-sweep-enabled）——测试/冒烟直调 service。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.admin.bg-audit-sweep-enabled", havingValue = "true")
public class BgAuditSweepTask {

    private final BreakGlassService breakGlassService;

    public BgAuditSweepTask(BreakGlassService breakGlassService) {
        this.breakGlassService = breakGlassService;
    }

    @Scheduled(cron = "0 20 2 * * ?")
    public void sweep() {
        Map<String, Object> out = breakGlassService.sweepAuditOverdue();
        log.info("bg audit overdue sweep done: {}", out);
    }
}
