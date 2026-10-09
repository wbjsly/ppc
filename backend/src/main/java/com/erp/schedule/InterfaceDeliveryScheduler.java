package com.erp.schedule;

import com.erp.service.intf.DeliveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 出站事件投递作业（spec interface-event-delivery，design D3）。
 * 用调度轮询替代 RabbitMQ（偏差表已记：消息总线未落地），投递语义与台账接口保持可平滑切换。
 * 单实例部署假设（本地 Docker），无分布式锁。
 */
@Slf4j
@Component
public class InterfaceDeliveryScheduler {

    private final DeliveryService deliveryService;

    public InterfaceDeliveryScheduler(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    /** 默认 5 秒一轮（可由 app.intf.delivery-sweep-ms 覆盖） */
    @Scheduled(fixedDelayString = "${app.intf.delivery-sweep-ms:5000}")
    public void deliver() {
        try {
            int handled = deliveryService.scanAndDeliver();
            if (handled > 0) {
                log.info("interface event delivery sweep done: handled={}", handled);
            }
        } catch (Exception e) {
            log.warn("interface event delivery sweep failed (will retry next tick): {}", e.getMessage());
        }
    }
}
