package com.erp.schedule;

import com.erp.service.scm.PartnerActivateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 伙伴入驻每日任务（add-partner-onboarding，design D4/D6）：
 * ① 过期激活 token 置失效（BR-4.9-11，幂等）
 * 审核催办/升级由审批底座既有 sweepTimeouts 覆盖（72h 提醒 / 7 天升级），
 * 本任务不重复扫描审批实例。
 */
@Slf4j
@Component
public class PartnerOnboardDailyTask {

    private final PartnerActivateService activateService;

    public PartnerOnboardDailyTask(PartnerActivateService activateService) {
        this.activateService = activateService;
    }

    /** 05:40（错开 05:00/05:30 既有任务） */
    @Scheduled(cron = "0 40 5 * * ?")
    public void sweep() {
        int n = activateService.sweepExpired();
        if (n > 0) {
            log.info("partner activate tokens expired swept: {}", n);
        }
    }
}
