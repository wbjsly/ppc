package com.erp.schedule;

import com.erp.service.intf.SlaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * SLA 采集与月报调度（spec interface-sla-monitoring 7.1/7.5，design D7）：
 * 每分钟采集四项指标并判定三级告警；每月 1 日 02:00 生成月度报告。
 */
@Slf4j
@Component
public class InterfaceSlaScheduler {

    private final SlaService slaService;

    public InterfaceSlaScheduler(SlaService slaService) {
        this.slaService = slaService;
    }

    /** FR-4.9-5-2：采集频率每分钟一次 */
    @Scheduled(fixedDelayString = "${app.intf.sla-sweep-ms:60000}")
    public void collect() {
        try {
            int n = slaService.collect();
            if (n > 0) {
                log.debug("interface sla collect done: written={}", n);
            }
        } catch (Exception e) {
            log.warn("interface sla collect failed: {}", e.getMessage());
        }
    }

    /** SOP-5.5-D 步骤2：每月 1 日 02:00 生成上月报告 */
    @Scheduled(cron = "0 0 2 1 * *")
    public void generateMonthlyReport() {
        try {
            Map<String, Object> result = slaService.generateReport(null);
            log.info("monthly interface SLA report generated: {}", result);
        } catch (Exception e) {
            log.warn("monthly interface SLA report failed: {}", e.getMessage());
        }
    }
}
