package com.erp.schedule;

import com.erp.service.approval.ApprovalEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批超时扫描（change add-quality-collaboration design D4）：
 *  - ACTIVE 节点超 approval-remind-hours（72h）→ REMIND_COUNT=1 提醒（幂等）
 *  - 超 approval-escalate-days（7 天）→ ESCALATED=1 + 生成升级待办（幂等）
 * 单实例部署假设（本地 Docker），无分布式锁。
 */
@Slf4j
@Component
public class ApprovalSweepScheduler {

    private final ApprovalEngine approvalEngine;

    public ApprovalSweepScheduler(ApprovalEngine approvalEngine) {
        this.approvalEngine = approvalEngine;
    }

    /** 每 10 分钟扫描一次（可由 app.qms.approval-sweep-ms 覆盖，单位毫秒） */
    @Scheduled(fixedDelayString = "${app.qms.approval-sweep-ms:600000}")
    public void sweepApprovalTimeouts() {
        try {
            int handled = approvalEngine.sweepTimeouts();
            if (handled > 0) {
                log.info("approval timeout sweep done: handled={}", handled);
            }
        } catch (Exception e) {
            log.warn("approval timeout sweep failed (will retry next tick): {}", e.getMessage());
        }
    }
}
