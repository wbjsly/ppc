package com.erp.schedule;

import com.erp.service.system.AccountLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 账号生命周期每日清扫（add-account-lifecycle-console design D5）：
 * 离职 24h 兜底强停 + 停用 90 天自动归档。
 * enabled 默认 false（app.admin.account-sweep-enabled）——测试与冒烟直调 service，生产开启。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.admin.account-sweep-enabled", havingValue = "true")
public class AccountSweepTask {

    private final AccountLifecycleService lifecycleService;

    public AccountSweepTask(AccountLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(cron = "0 30 2 * * ?")
    public void sweep() {
        Map<String, Object> out = lifecycleService.sweep();
        log.info("account sweep done: {}", out);
    }
}
