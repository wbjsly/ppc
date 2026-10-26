package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmTimesheetDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmTimesheet;
import com.erp.entity.pm.PmWbs;
import com.erp.service.pm.PmHoursVarianceService;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 工时每日扫描（add-timesheet-management，04:00）。
 * 三类收敛：1) 偏差分级预警+纠偏升级（PmHoursVarianceService.dailyScan）
 * 2) 昨日未录提醒本人（hr_remind UK 防重） 3) 连续 3 工作日未录提醒项目经理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmHoursDailyTask {

    /** 连续未录升级项目经理的工作日阈值（FR-4.8-4-2 异常处理） */
    private static final int MISSING_STREAK_DAYS = 3;

    private final PmHoursVarianceService varianceService;
    private final PmWbsDao wbsDao;
    private final PmProjectDao projectDao;
    private final PmTimesheetDao tsDao;
    private final HrRemindDao remindDao;
    private final NoticeService noticeService;

    @Scheduled(cron = "0 0 4 * * ?")
    public void run() {
        try {
            Map<String, Object> scan = varianceService.dailyScan();
            log.info("pm hours daily scan: yellow={} red={} escalated={}",
                    scan.get("yellow"), scan.get("red"), scan.get("escalated"));
        } catch (Exception e) {
            log.error("pm hours variance scan failed", e);
        }
        try {
            remindMissing();
        } catch (Exception e) {
            log.error("pm hours missing remind failed", e);
        }
    }

    /** 未录提醒：在项任务负责人昨日无工时单 → 提醒本人；连续 3 工作日 → 提醒项目经理 */
    private void remindMissing() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        // 在项（ACTIVE 项目）任务负责人集合
        List<PmProject> actives = projectDao.selectList(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getDelFlag, "0")
                .eq(PmProject::getStatus, PmProject.ST_ACTIVE));
        Set<String> owners = new LinkedHashSet<>();
        for (PmProject p : actives) {
            for (PmWbs w : wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                    .eq(PmWbs::getProjectId, p.getId())
                    .eq(PmWbs::getLevel, PmWbs.LV_TASK)
                    .eq(PmWbs::getDelFlag, "0"))) {
                if (w.getOwnerUserId() != null && !w.getOwnerUserId().isBlank()) {
                    owners.add(w.getOwnerUserId());
                }
            }
        }
        for (String owner : owners) {
            // 昨日未录 → 提醒本人（hr_remind UK(REMIND_TYPE,EMP_ID,DATE) 防重）
            if (!hasSheet(owner, yesterday)) {
                insertRemind(owner, yesterday, "昨日（" + yesterday + "）未提交工时单，请补录");
                // 连续 3 工作日未录 → 提醒项目经理
                if (missingStreak(owner) >= MISSING_STREAK_DAYS) {
                    PmProject first = firstActiveOf(owner);
                    if (first != null && first.getPmUserId() != null) {
                        noticeService.push("ROLE_PM", null,
                                "成员连续未提交工时：" + owner,
                                "员工 " + owner + " 已连续 " + MISSING_STREAK_DAYS
                                        + " 个工作日未提交工时单（项目：" + first.getName() + "），请督促。",
                                "PmHoursMissingStreak", owner + ":" + yesterday);
                    }
                }
            }
        }
    }

    /** 连续未录工作日数（从昨日往前数，最多数到 10 个工作日防御） */
    private int missingStreak(String owner) {
        int streak = 0;
        LocalDate d = LocalDate.now().minusDays(1);
        while (streak < 10) {
            if (d.getDayOfWeek().getValue() < 6) { // 工作日
                if (hasSheet(owner, d)) {
                    break;
                }
                streak++;
            }
            d = d.minusDays(1);
        }
        return streak;
    }

    private boolean hasSheet(String empId, LocalDate date) {
        Long n = tsDao.selectCount(new LambdaQueryWrapper<PmTimesheet>()
                .eq(PmTimesheet::getEmpId, empId)
                .eq(PmTimesheet::getWorkDate, date)
                .eq(PmTimesheet::getDelFlag, "0"));
        return n != null && n > 0;
    }

    private PmProject firstActiveOf(String owner) {
        for (PmWbs w : wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getOwnerUserId, owner)
                .eq(PmWbs::getLevel, PmWbs.LV_TASK)
                .eq(PmWbs::getDelFlag, "0"))) {
            PmProject p = projectDao.selectById(w.getProjectId());
            if (p != null && PmProject.ST_ACTIVE.equals(p.getStatus())) {
                return p;
            }
        }
        return null;
    }

    private void insertRemind(String empId, LocalDate date, String detail) {
        // UK(REMIND_TYPE,EMP_ID,DATE) 防重：先查再插（同日重跑不产生异常噪音）
        Long exists = remindDao.selectCount(new LambdaQueryWrapper<HrRemind>()
                .eq(HrRemind::getRemindType, HrRemind.TYPE_HOURS_MISSING)
                .eq(HrRemind::getEmpId, empId)
                .eq(HrRemind::getRemindDate, date));
        if (exists != null && exists > 0) {
            return;
        }
        HrRemind r = new HrRemind();
        r.setId(UUID.randomUUID().toString().replace("-", ""));
        r.setRemindType(HrRemind.TYPE_HOURS_MISSING);
        r.setEmpId(empId);
        r.setDetail(detail);
        r.setRemindDate(date);
        r.setCreateBy("system");
        r.setCreateDate(LocalDateTime.now());
        remindDao.insert(r);
    }
}
