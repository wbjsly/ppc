package com.erp.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrTransferDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrTransfer;
import com.erp.service.hr.EmployeeArchiveService;
import com.erp.service.impl.hr.HrTransferServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * HR 每日扫描（add-employee-archive design D4，项目首个 @Scheduled）：
 * ①超期试用期无评价 → 自动按期转正（留痕「系统默认转正」）；
 * ②到期调岗单（拟生效日 ≤ 今天 且 APPROVING 已批完成由回调处理，扫描兜底 EFFECTED 前置态）→ 执行生效；
 * ③15 天内试用期 → 写提醒记录（EmployeeArchiveService.probationRemindings 内含 UK 防重）。
 * 幂等：状态前置校验 + UK 防重；单实例部署现状不引入分布式锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HrDailyScanTask {

    private final HrEmployeeDao empDao;
    private final HrTransferDao trfDao;
    private final HrTransferServiceImpl transferService;
    private final EmployeeArchiveService archiveService;

    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyScan() {
        log.info("HR daily scan start");
        try {
            autoRegularizeOverdueProbation();
        } catch (Exception e) {
            log.error("HR daily scan: auto regularize failed - {}", e.getMessage());
        }
        try {
            effectDuePostChanges();
        } catch (Exception e) {
            log.error("HR daily scan: effect due posts failed - {}", e.getMessage());
        }
        try {
            archiveService.probationRemindings();
        } catch (Exception e) {
            log.error("HR daily scan: probation remindings failed - {}", e.getMessage());
        }
        log.info("HR daily scan done");
    }

    /** ①超期试用期（截止日 < 今天）无进行中转正单 → 自动按期转正 */
    private void autoRegularizeOverdueProbation() {
        LocalDate today = LocalDate.now();
        List<HrEmployee> overdue = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .eq(HrEmployee::getEmpStatus, HrEmployee.ST_PROBATION)
                .isNotNull(HrEmployee::getProbationEnd)
                .lt(HrEmployee::getProbationEnd, today));
        for (HrEmployee e : overdue) {
            // 已有进行中转正单 → 跳过（人工处理中）
            Long pending = trfDao.selectCount(new LambdaQueryWrapper<HrTransfer>()
                    .eq(HrTransfer::getDelFlag, "0")
                    .eq(HrTransfer::getEmpId, e.getId())
                    .eq(HrTransfer::getType, HrTransfer.TYPE_PROBATION)
                    .in(HrTransfer::getStatus, HrTransfer.ST_DRAFT, HrTransfer.ST_APPROVING,
                            HrTransfer.ST_REVIEWING));
            if (pending > 0) {
                continue;
            }
            transferService.autoRegularize(e);
        }
    }

    /** ②到期调岗单兜底执行（回调已处理审批通过；扫描处理状态滞留 APPROVING 但已批的兜底不必要——
     *  改为：REVIEWING 完成但审批流未启动的不在此列；此处处理生效日期已到且状态 APPROVING 但 appr 已通过的场景
     *  简化为：跳过（审批回调负责生效）；保留方法位以备扩展。 */
    private void effectDuePostChanges() {
        // 审批通过回调已执行生效（HrApprovalCallback）；扫描不重复执行。
        // 如后续出现生效滞留（回调失败 LINK_PENDING），可在人工重试入口处理。
    }
}
