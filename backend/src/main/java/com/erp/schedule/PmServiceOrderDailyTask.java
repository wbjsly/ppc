package com.erp.schedule;

import com.erp.service.impl.pm.PmServiceOrderServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 服务工单每日扫描（add-service-order，05:00，design D2）。
 * A) SLA 超时逐级升级（响应→服务主管，解决→销售经理；so_sla_log 唯一键幂等）；
 * B) 确认超期默认关闭（5 工作日 + 2 次提醒后关闭，标注默认关闭）；
 * C) 回访到期提醒（关闭后 3 工作日无回访）；
 * D) 改进任务逾期升级销售经理（BR-4.8-06）。
 * 四段独立 try/catch：单段失败不影响其余（沿 PmIpDailyTask 惯例）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmServiceOrderDailyTask {

    private final PmServiceOrderServiceImpl serviceOrderService;

    @Scheduled(cron = "0 0 5 * * ?")
    public void run() {
        try {
            int n = serviceOrderService.escalateOverdue(LocalDateTime.now());
            if (n > 0) {
                log.info("service-order sla escalate done, {}", n);
            }
        } catch (Exception e) {
            log.error("service-order sla escalate failed", e);
        }
        try {
            int n = serviceOrderService.defaultCloseExpired(LocalDateTime.now());
            if (n > 0) {
                log.info("service-order default close done, {}", n);
            }
        } catch (Exception e) {
            log.error("service-order default close failed", e);
        }
        try {
            int n = serviceOrderService.remindSurveyDue(LocalDateTime.now());
            if (n > 0) {
                log.info("service-order survey remind done, {}", n);
            }
        } catch (Exception e) {
            log.error("service-order survey remind failed", e);
        }
        try {
            int n = serviceOrderService.escalateImproveOverdue(LocalDateTime.now());
            if (n > 0) {
                log.info("service-order improve overdue escalate done, {}", n);
            }
        } catch (Exception e) {
            log.error("service-order improve overdue escalate failed", e);
        }
    }
}
