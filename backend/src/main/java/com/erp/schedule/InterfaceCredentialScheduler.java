package com.erp.schedule;

import com.erp.service.intf.CredentialService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 凭证到期治理调度（spec api-credential-management 3.3）：
 * 30/7/1 日分级轮换提醒、到期自动 EXPIRED、双凭证灰度观察期推进、DEPRECATED 回滚窗口结束自动吊销。
 * 单实例部署假设（本地 Docker），无分布式锁 —— 同 ApprovalSweepScheduler 范式。
 */
@Slf4j
@Component
public class InterfaceCredentialScheduler {

    private final CredentialService credentialService;

    public InterfaceCredentialScheduler(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** 每小时扫描一次（可由 app.intf.credential-sweep-ms 覆盖，单位毫秒） */
    @Scheduled(fixedDelayString = "${app.intf.credential-sweep-ms:3600000}")
    public void sweepCredentialExpiry() {
        try {
            int handled = credentialService.sweepExpiry();
            if (handled > 0) {
                log.info("credential expiry sweep done: handled={}", handled);
            }
        } catch (Exception e) {
            log.warn("credential expiry sweep failed (will retry next tick): {}", e.getMessage());
        }
    }
}
