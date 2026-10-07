package com.erp.schedule;

import com.erp.service.sd.ShipmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时未签收预警扫描（tasks 9.8，spec sales-shipment FR-4.3-6-7）。
 * 每日 08:20 扫描 CONFIRMED 且超过 SIGN_TIMEOUT_DAYS 的发货单，推送销售跟进。
 */
@Slf4j
@Component
public class ShipmentSignScheduler {

    private final ShipmentService shipmentService;

    public ShipmentSignScheduler(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @Scheduled(cron = "0 20 8 * * ?")
    public void sweep() {
        try {
            int n = shipmentService.sweepSignTimeout();
            if (n > 0) {
                log.info("sign timeout sweep: {} shipments warned", n);
            }
        } catch (Exception e) {
            log.warn("sign timeout sweep failed: {}", e.getMessage());
        }
    }
}
