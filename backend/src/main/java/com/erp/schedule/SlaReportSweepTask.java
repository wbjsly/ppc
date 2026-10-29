package com.erp.schedule;

import com.erp.service.SlaReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 月度 SLA 报告生成扫描（add-sla-report-console design D3，5.6 业务流程 #5）：
 * 每月 1 日 02:00 生成上月报告（补缺不覆盖）。enabled 默认 false
 * （app.admin.sla-sweep-enabled）——测试/冒烟直调 service 或手动端点。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.admin.sla-sweep-enabled", havingValue = "true")
public class SlaReportSweepTask {

    private final SlaReportService slaReportService;

    public SlaReportSweepTask(SlaReportService slaReportService) {
        this.slaReportService = slaReportService;
    }

    @Scheduled(cron = "0 0 2 1 * ?")
    public void sweep() {
        Map<String, Object> out = slaReportService.sweepGenerate();
        log.info("sla monthly report sweep done: {}", out);
    }
}
