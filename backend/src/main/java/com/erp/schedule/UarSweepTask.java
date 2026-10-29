package com.erp.schedule;

import com.erp.service.UarService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * UAR 逾期升级扫描（add-access-review-console spec BR-5.6-10）：
 * 截止前 2 个工作日完成率 <100% → 升级部门负责人 + 抄送内审。
 * enabled 默认 false（app.admin.uar-sweep-enabled）——测试/冒烟直调 service。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.admin.uar-sweep-enabled", havingValue = "true")
public class UarSweepTask {

    private final UarService uarService;

    public UarSweepTask(UarService uarService) {
        this.uarService = uarService;
    }

    @Scheduled(cron = "0 30 2 * * ?")
    public void sweep() {
        Map<String, Object> out = uarService.sweepOverdue();
        log.info("uar overdue sweep done: {}", out);
    }
}
