package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.hr.HrPayDiffDao;
import com.erp.dao.hr.HrPayrollRunDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.hr.HrSocialPolicyDao;
import com.erp.entity.hr.HrPayDiff;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.hr.HrSocialPolicy;
import com.erp.service.hr.HrSocialInsuranceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 薪酬域每日扫描（add-payroll-management 组 6）：
 * ①6 月社保基数调整提醒（委托 socialService）②PayrollRun 审批超时催办（2 工作日）
 * ③差异处理超期催办（5 工作日时限已过）。全部幂等可重跑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrPayrollDailyTask {

    private final HrSocialInsuranceService socialService;
    private final HrPayrollRunDao runDao;
    private final HrPayDiffDao diffDao;
    private final HrRemindDao remindDao;

    /** 03:00 薪酬域扫描簇。 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void payrollDailyScan() {
        try {
            int n = socialService.julyBaseAdjustScan();
            if (n > 0) {
                log.info("daily payroll: social base adjust reminded {}", n);
            }
        } catch (Exception e) {
            log.error("daily payroll social scan failed: {}", e.getMessage());
        }
        try {
            int n = approvalRemindScan();
            if (n > 0) {
                log.info("daily payroll: approval remind {}", n);
            }
        } catch (Exception e) {
            log.error("daily payroll approval remind failed: {}", e.getMessage());
        }
        try {
            int n = diffOverdueScan();
            if (n > 0) {
                log.info("daily payroll: diff overdue remind {}", n);
            }
        } catch (Exception e) {
            log.error("daily payroll diff remind failed: {}", e.getMessage());
        }
    }

    /** PayrollRun 提交审批后满 2 工作日未审 → 催办 HR 经理（hr_remind 主管×日聚合防轰炸）。 */
    int approvalRemindScan() {
        LocalDate today = LocalDate.now();
        List<HrPayrollRun> pending = runDao.selectList(new LambdaQueryWrapper<HrPayrollRun>()
                .eq(HrPayrollRun::getStatus, HrPayrollRun.ST_SUBMITTING)
                .eq(HrPayrollRun::getDelFlag, "0"));
        int reminded = 0;
        for (HrPayrollRun run : pending) {
            if (run.getSubmitAt() == null) {
                continue;
            }
            if (workDaysBetween(run.getSubmitAt().toLocalDate(), today) < 2) {
                continue;
            }
            boolean exists = remindDao.exists(new LambdaQueryWrapper<HrRemind>()
                    .eq(HrRemind::getRemindType, "PAYROLL_APPROVE_OVERDUE")
                    .eq(HrRemind::getEmpId, "HR_MGR")
                    .eq(HrRemind::getRemindDate, today));
            if (exists) {
                continue;
            }
            HrRemind r = new HrRemind();
            r.setId(uuid());
            r.setRemindType("PAYROLL_APPROVE_OVERDUE");
            r.setEmpId("HR_MGR");
            r.setEmpName("HR 经理");
            r.setRemindDate(today);
            r.setDetail("PayrollRun 超时未审批：" + run.getRunNo() + "（" + run.getPeriod()
                    + "，实发合计 " + run.getNetTotal() + "），请在发放日前 2 个工作日完成审批");
            r.setCreateBy("scan");
            r.setCreateDate(LocalDateTime.now());
            remindDao.insert(r);
            reminded++;
        }
        return reminded;
    }

    /** 差异处理超过 5 工作日时限未关闭 → 催办核算员。 */
    int diffOverdueScan() {
        LocalDate today = LocalDate.now();
        List<HrPayDiff> openDiffs = diffDao.selectList(new LambdaQueryWrapper<HrPayDiff>()
                .eq(HrPayDiff::getStatus, HrPayDiff.ST_OPEN)
                .eq(HrPayDiff::getDelFlag, "0")
                .le(HrPayDiff::getDeadline, today));
        int reminded = 0;
        for (HrPayDiff diff : openDiffs) {
            boolean exists = remindDao.exists(new LambdaQueryWrapper<HrRemind>()
                    .eq(HrRemind::getRemindType, "PAY_DIFF_OVERDUE")
                    .eq(HrRemind::getEmpId, diff.getDiffNo())
                    .eq(HrRemind::getRemindDate, today));
            if (exists) {
                continue;
            }
            HrRemind r = new HrRemind();
            r.setId(uuid());
            r.setRemindType("PAY_DIFF_OVERDUE");
            r.setEmpId(diff.getDiffNo());
            r.setEmpName("薪酬核算员");
            r.setRemindDate(today);
            r.setDetail("发放差异超期未处理：" + diff.getDiffNo() + "（" + diff.getDiffType()
                    + "，时限 " + diff.getDeadline() + "，处理方案 " + diff.getResolvePlan() + "）");
            r.setCreateBy("scan");
            r.setCreateDate(LocalDateTime.now());
            remindDao.insert(r);
            reminded++;
        }
        return reminded;
    }

    /** 跳周末工作日口径（与 overtime 催办一致；法定假调休不感知留桩）。 */
    private static long workDaysBetween(LocalDate from, LocalDate to) {
        long days = 0;
        LocalDate d = from.plusDays(1);
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek().getValue() < 6) {
                days++;
            }
            d = d.plusDays(1);
        }
        return days;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
