package com.erp.schedule;

import com.erp.service.inv.TransferOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 调拨在途超期扫描（add-outbound-workbench，spec transfer-order C-4.4-09）。
 * 每日 08:40 扫描 OUT_POSTED 且超 TRANSIT_ALERT_DAYS（默认 30 天）未入库的调拨单：
 * 置 SUSPENDED_FLAG 并通知双方仓库主管；flag 前置条件保证幂等（重复扫描不重复挂起/通知）。
 */
@Slf4j
@Component
public class TransferTransitScheduler {

    private final TransferOrderService transferOrderService;

    public TransferTransitScheduler(TransferOrderService transferOrderService) {
        this.transferOrderService = transferOrderService;
    }

    @Scheduled(cron = "0 40 8 * * ?")
    public void sweep() {
        try {
            int n = transferOrderService.sweepTransitOverdue();
            if (n > 0) {
                log.info("transit overdue sweep: {} transfer orders suspended", n);
            }
        } catch (Exception e) {
            log.warn("transit overdue sweep failed: {}", e.getMessage());
        }
    }
}
