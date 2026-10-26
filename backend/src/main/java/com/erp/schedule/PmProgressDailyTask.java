package com.erp.schedule;

import com.erp.service.pm.PmMilestoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 项目进度每日扫描（add-milestone-management，design D4；03:30）。
 * 三类预警：审批超时（APPROVED 超 3 工作日）/ L4 滞后（>5 工作日）/ 升级滞后（>15 工作日），
 * 幂等靠标记列（DELAY_NOTIFIED/ESCALATED_NOTIFIED/OVERDUE_REMINDED）+ hr_remind UK 双保险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmProgressDailyTask {

    private final PmMilestoneService milestoneService;

    @Scheduled(cron = "0 30 3 * * ?")
    public void run() {
        try {
            Map<String, Object> out = milestoneService.dailyScan();
            log.info("pm progress daily scan: approveOverdue={} delayL4={} escalated={}",
                    out.get("approveOverdue"), out.get("delayL4"), out.get("escalated"));
        } catch (Exception e) {
            log.error("pm progress daily scan failed", e);
        }
    }
}
