package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.bi.BiAdhocTaskDao;
import com.erp.entity.bi.AdhocTask;
import com.erp.service.bi.TempReportService;
import com.erp.service.impl.bi.AdhocQueryServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 即席查询调度（add-adhoc-query-console design D4）：
 * ① 10s worker：QUEUED 按 PRIORITY+时间排序、并发 ≤2 → 按属主治理执行 → 完成站内通知；
 * ② 每日 02:40：7 天前结果集清理（行保留作审计）+ REJECTED 频次安全提示；
 * ③ 每日 03:00：临时报表生命周期（到期归档 + 前 3 天提醒）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdhocScheduler {

    /** worker 并发上限（与导出队列 3 错开，沙箱资源隔离语义） */
    private static final int MAX_CONCURRENT = 2;
    private static final int BATCH = 5;

    private final AdhocQueryServiceImpl adhocService;
    private final BiAdhocTaskDao adhocTaskDao;
    private final TempReportService tempReportService;
    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelay = 10_000)
    public void drainOnce() {
        try {
            long running = adhocTaskDao.selectCount(new LambdaQueryWrapper<AdhocTask>()
                    .eq(AdhocTask::getStatus, AdhocTask.ST_RUNNING));
            int slots = MAX_CONCURRENT - (int) running;
            if (slots <= 0) {
                return;
            }
            List<AdhocTask> due = adhocTaskDao.selectList(new LambdaQueryWrapper<AdhocTask>()
                    .eq(AdhocTask::getStatus, AdhocTask.ST_QUEUED)
                    .orderByAsc(AdhocTask::getPriority)
                    .orderByAsc(AdhocTask::getCreateDate)
                    .last("LIMIT " + Math.min(slots, BATCH)));
            for (AdhocTask t : due) {
                adhocService.executeTaskById(t.getId());
                notifyIfDone(t.getId());
            }
        } catch (Exception e) {
            log.warn("adhoc worker error: {}", e.getMessage());
        }
    }

    /** 完成通知（精准到属主，spec：完成后站内通知用户） */
    private void notifyIfDone(String taskId) {
        AdhocTask a = adhocTaskDao.selectById(taskId);
        if (a == null || !AdhocTask.ST_DONE.equals(a.getStatus())
                || Boolean.TRUE.equals(a.getNotifyFlag())) {
            return;
        }
        jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, "
                        + "BIZ_TYPE, BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?,?,?,?,? ,?,?,?,NOW(),'0',0)",
                UUID.randomUUID().toString().replace("-", ""), a.getUserName(),
                "即席查询完成", "任务 " + a.getTaskNo() + " 已完成（"
                        + a.getActualRows() + " 行），可在自助查询-我的任务查看结果",
                "AdhocQueryDone", a.getId(), "0", "system");
        a.setNotifyFlag(true);
        adhocTaskDao.updateById(a);
    }

    /** 每日 02:40：结果集清理 + REJECTED 频次安全提示（C-4.10-10 留痕保留） */
    @Scheduled(cron = "0 40 2 * * ?")
    public void dailyCleanup() {
        int cleaned = jdbc.update(
                "UPDATE erp_bi_adhoc_task SET RESULT_JSON=NULL "
                        + "WHERE RESULT_JSON IS NOT NULL AND CREATE_DATE < ?",
                LocalDateTime.now().minusDays(7));
        // 当日 REJECTED ≥10 的用户 → 安全提示（恶意高频/全表扫描习惯）
        List<String> flagged = jdbc.queryForList(
                "SELECT USER_NAME FROM erp_bi_adhoc_task WHERE STATUS='REJECTED' "
                        + "AND CREATE_DATE >= CURDATE() GROUP BY USER_NAME "
                        + "HAVING COUNT(*) >= 10", String.class);
        for (String u : flagged) {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, "
                            + "BIZ_TYPE, BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?,?,?,?,? ,?,?,?,NOW(),'0',0)",
                    UUID.randomUUID().toString().replace("-", ""), u,
                    "即席查询安全提示", "今日您的查询多次被预检拦截，请优化过滤条件或申请正式报表",
                    "AdhocQuerySecurity", u, "0", "system");
        }
        if (cleaned > 0 || !flagged.isEmpty()) {
            log.info("adhoc dailyCleanup: cleaned={}, flagged={}", cleaned, flagged.size());
        }
    }

    /** 每日 03:00：临时报表生命周期（BR-4.10-34） */
    @Scheduled(cron = "0 0 3 * * ?")
    public void tempReportLifecycle() {
        try {
            java.util.Map<String, Object> result = tempReportService.sweepLifecycle();
            log.info("temp report lifecycle: {}", result);
        } catch (Exception e) {
            log.warn("temp report lifecycle error: {}", e.getMessage());
        }
    }
}
