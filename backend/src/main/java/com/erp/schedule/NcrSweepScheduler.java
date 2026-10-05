package com.erp.schedule;

import com.erp.service.qms.NcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * NCR 超时/超期扫描（tasks 6.2 / 6.6，spec ncr-management）：
 *  - 评审分级时限（Critical 4h / Major 24h / Minor 48h）：超 1 倍升质量经理、2 倍升质量总监（幂等）
 *  - 超 NCR_CLOSE_DAYS=30 天未关闭：升质量总监 + 抄送采购经理 + 冻结供应商绩效发布（BR-4.2-27），
 *    之后每 7 天重复提醒，关闭解除
 * 单实例部署假设（本地 Docker），无分布式锁；标记位保证重复扫描幂等。
 */
@Slf4j
@Component
public class NcrSweepScheduler {

    private final NcrService ncrService;

    public NcrSweepScheduler(NcrService ncrService) {
        this.ncrService = ncrService;
    }

    /** 每 5 分钟扫描（可由 app.qms.ncr-sweep-ms 覆盖，单位毫秒） */
    @Scheduled(fixedDelayString = "${app.qms.ncr-sweep-ms:300000}")
    public void sweepNcr() {
        try {
            int review = ncrService.sweepReviewTimeouts();
            int overdue = ncrService.sweepOverdueEscalation();
            if (review > 0 || overdue > 0) {
                log.info("NCR sweep done: reviewEscalated={}, overdueHandled={}", review, overdue);
            }
        } catch (Exception e) {
            log.warn("NCR sweep failed (will retry next tick): {}", e.getMessage());
        }
    }
}
