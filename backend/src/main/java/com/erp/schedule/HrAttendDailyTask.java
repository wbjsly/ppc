package com.erp.schedule;

import com.erp.service.hr.HrCompTimeService;
import com.erp.service.hr.HrLeaveBalanceService;
import com.erp.service.hr.HrOvertimeConfirmService;
import com.erp.service.hr.HrOvertimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 考勤-加班-假期每日扫描编排（add-attendance-management D2/D4/D5，幂等调度）：
 * ①异常识别（HrAttendanceScanTask 01:30 独立）②加班确认 ③调休到期提醒+清零
 * ④年假年度发放+当年清零。全部幂等可重跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrAttendDailyTask {

    private final HrOvertimeConfirmService confirmService;
    private final HrCompTimeService compTimeService;
    private final HrLeaveBalanceService balanceService;
    private final HrOvertimeService overtimeService;

    /** 加班确认扫描（02:00，晚于异常识别）。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void overtimeConfirmScan() {
        try {
            int n = confirmService.confirmScan();
            if (n > 0) {
                log.info("daily overtime confirm: {} created", n);
            }
        } catch (Exception e) {
            log.error("daily overtime confirm failed: {}", e.getMessage());
        }
    }

    /** 调休到期提醒 + 过期清零（02:10）。 */
    @Scheduled(cron = "0 10 2 * * ?")
    public void compTimeExpiryScan() {
        try {
            var out = compTimeService.expiryScan();
            if (((Number) out.get("reminded")).intValue() > 0
                    || ((Number) out.get("expired")).intValue() > 0) {
                log.info("daily comp time expiry: {}", out);
            }
        } catch (Exception e) {
            log.error("daily comp time expiry failed: {}", e.getMessage());
        }
    }

    /** 年假年度发放 + 当年清零（02:20；发放幂等同年跳过）。 */
    @Scheduled(cron = "0 20 2 * * ?")
    public void annualLeaveScan() {
        try {
            var grant = balanceService.annualGrantScan();
            var expire = balanceService.annualExpireScan();
            if (((Number) grant.get("granted")).intValue() > 0
                    || ((Number) expire.get("expired")).intValue() > 0) {
                log.info("daily annual leave: grant={} expire={}", grant, expire);
            }
        } catch (Exception e) {
            log.error("daily annual leave failed: {}", e.getMessage());
        }
    }

    /** 加班审批超时催办扫描（02:30，add-overtime-followup FR-4.7-6-2）。 */
    @Scheduled(cron = "0 30 2 * * ?")
    public void otApprovalRemindScan() {
        try {
            int n = overtimeService.approvalRemindScan();
            if (n > 0) {
                log.info("daily ot approval remind: {} supervisors reminded", n);
            }
        } catch (Exception e) {
            log.error("daily ot approval remind failed: {}", e.getMessage());
        }
    }
}
