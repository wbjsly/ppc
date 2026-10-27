package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.ClosureCheckDao;
import com.erp.dao.pm.ClosureRemediationDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.ClosureCheck;
import com.erp.entity.pm.ClosureRemediation;
import com.erp.entity.pm.PmProject;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 结项遗留工单逾期扫描（add-project-closure，05:30，design D6）。
 * OPEN 且 DUE_DATE < now 且未升级 → 通知 ROLE_PMO + 置 ESCALATED_FLAG=1（幂等，BR-4.8-28）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PmClosureDailyTask {

    private final ClosureRemediationDao remediationDao;
    private final ClosureCheckDao checkDao;
    private final PmProjectDao projectDao;
    private final NoticeService noticeService;

    @Scheduled(cron = "0 30 5 * * ?")
    public void run() {
        try {
            escalateOverdue();
        } catch (Exception e) {
            log.error("closure remediation overdue scan failed", e);
        }
    }

    /** ADMIN 手动触发 + 05:30 日报共用：OPEN 逾期未升级 → 通知 ROLE_PMO（幂等）。返回处理条数。 */
    public int escalateOverdue() {
        List<ClosureRemediation> overdue = remediationDao.selectList(
                new LambdaQueryWrapper<ClosureRemediation>()
                        .eq(ClosureRemediation::getStatus, ClosureRemediation.ST_OPEN)
                        .eq(ClosureRemediation::getEscalatedFlag, "0")
                        .eq(ClosureRemediation::getDelFlag, "0")
                        .lt(ClosureRemediation::getDueDate, LocalDateTime.now()));
        for (ClosureRemediation r : overdue) {
            ClosureCheck c = checkDao.selectById(r.getCheckId());
            PmProject p = c == null ? null : projectDao.selectById(c.getProjectId());
            String projName = p == null ? r.getProjectId() : p.getName();
            noticeService.push("ROLE_PMO", null,
                    "结项遗留工单逾期：" + projName,
                    "遗留事项「" + r.getContent() + "」（责任人 " + r.getOwnerName()
                            + "）已超 90 天期限未关闭（应于 " + r.getDueDate() + " 前闭环），"
                            + "请督办（BR-4.8-28）。",
                    "PmClosureRemediationOverdue", r.getId());
            r.setEscalatedFlag("1");
            r.setEscalatedAt(LocalDateTime.now());
            r.setUpdateBy("system");
            r.setUpdateDate(LocalDateTime.now());
            remediationDao.updateById(r);
        }
        if (!overdue.isEmpty()) {
            log.info("closure remediation overdue escalated: {}", overdue.size());
        }
        return overdue.size();
    }
}
