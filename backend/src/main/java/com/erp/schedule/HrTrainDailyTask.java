package com.erp.schedule;

import com.erp.service.hr.HrCertService;
import com.erp.service.hr.HrTrainPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 培训认证每日任务（add-training-certification）：
 * ①证书状态幂等翻转（VALID→REVIEW_DUE→EXPIRED）+ 复审提醒推送（BR-4.7-06）；
 * ②培训任务临期/逾期催办（hr_remind + 站内推送）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrTrainDailyTask {

    private final HrCertService certService;
    private final HrTrainPlanService trainPlanService;

    /** 证书翻转 + 培训催办（03:00，幂等可重跑） */
    @Scheduled(cron = "0 0 3 * * ?")
    public void dailyScan() {
        try {
            Map<String, Object> flip = certService.dailyFlipScan();
            if (((Number) flip.get("reviewDue")).intValue() > 0
                    || ((Number) flip.get("expired")).intValue() > 0) {
                log.info("cert daily flip: reviewDue={} expired={}", flip.get("reviewDue"), flip.get("expired"));
            }
        } catch (Exception e) {
            log.error("cert flip scan failed: {}", e.getMessage());
        }
        try {
            Map<String, Object> remind = trainPlanService.remindScan();
            if (((Number) remind.get("reminded")).intValue() > 0
                    || ((Number) remind.get("overdue")).intValue() > 0) {
                log.info("train task remind: reminded={} overdue={}", remind.get("reminded"), remind.get("overdue"));
            }
        } catch (Exception e) {
            log.error("train remind scan failed: {}", e.getMessage());
        }
    }
}
