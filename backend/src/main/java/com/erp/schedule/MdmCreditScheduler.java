package com.erp.schedule;

import com.erp.service.mdm.MdmCreditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 信用额度定时任务（design D4）：
 *  - 临时额度到期回滚（BR-4.1-33），与查询懒校验双保险、幂等
 *  - 复审超期懒压缩由查询侧触发，此处顺带兜底（幂等）
 * 单实例部署假设（本地 Docker），无分布式锁。
 */
@Slf4j
@Component
public class MdmCreditScheduler {

    private final MdmCreditService creditService;

    public MdmCreditScheduler(MdmCreditService creditService) {
        this.creditService = creditService;
    }

    /** 每小时扫描（可由 app.mdm.credit-sweep-interval-ms 覆盖，单位毫秒） */
    @Scheduled(fixedDelayString = "${app.mdm.credit-sweep-interval-ms:3600000}")
    public void sweepCredit() {
        try {
            int rolled = creditService.sweepExpiredTemp();
            int compressed = creditService.compressOverdueReviews();
            if (rolled > 0 || compressed > 0) {
                log.info("credit sweep done: tempRollback={}, reviewCompressed={}", rolled, compressed);
            }
        } catch (Exception e) {
            log.warn("credit sweep failed (will retry next tick): {}", e.getMessage());
        }
    }
}
