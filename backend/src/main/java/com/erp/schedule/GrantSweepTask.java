package com.erp.schedule;

import com.erp.service.GrantSweepService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 到期回收每日清扫（add-permission-approval-flow design D6）：
 * 到期自动回收 + 到期前 7 日续期提醒。
 * enabled 默认 false（app.admin.grant-sweep-enabled）——测试与冒烟直调 service，生产开启。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.admin.grant-sweep-enabled", havingValue = "true")
public class GrantSweepTask {

    private final GrantSweepService grantSweepService;

    public GrantSweepTask(GrantSweepService grantSweepService) {
        this.grantSweepService = grantSweepService;
    }

    @Scheduled(cron = "0 10 2 * * ?")
    public void sweep() {
        Map<String, Object> out = grantSweepService.sweep(LocalDateTime.now());
        log.info("grant reclaim sweep done: {}", out);
    }
}
