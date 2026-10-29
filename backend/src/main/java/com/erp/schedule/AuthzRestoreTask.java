package com.erp.schedule;

import com.erp.service.AuthzIncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 降权到期恢复扫描（add-authz-detection-console spec authz-detection，design D7）：
 * 默认 7 天到期自动恢复。enabled 默认 false（app.admin.authz-restore-enabled）——
 * 测试/冒烟直调 service 或手动端点。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.admin.authz-restore-enabled", havingValue = "true")
public class AuthzRestoreTask {

    private final AuthzIncidentService authzIncidentService;

    public AuthzRestoreTask(AuthzIncidentService authzIncidentService) {
        this.authzIncidentService = authzIncidentService;
    }

    @Scheduled(cron = "0 15 3 * * ?")
    public void sweep() {
        Map<String, Object> out = authzIncidentService.restoreExpired();
        log.info("authz restriction restore sweep done: {}", out);
    }
}
