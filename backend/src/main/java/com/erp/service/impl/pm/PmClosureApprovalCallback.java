package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.pm.ClosureCheckDao;
import com.erp.dao.pm.ClosureCheckItemDao;
import com.erp.dao.pm.ClosureRemediationDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.entity.pm.ClosureCheck;
import com.erp.entity.pm.ClosureCheckItem;
import com.erp.entity.pm.ClosureRemediation;
import com.erp.entity.pm.PmProject;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.system.NoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 结项审批回调（add-project-closure，design D3）。
 * BIZ_TYPE=ProjectClosure：
 *  pass  → DRAFT 遗留项转 OPEN（+90 天）→ 项目置 CLOSED → 生成资源释放清单快照
 *         （参与人/工时汇总，取自工时行；capacity 为通用日历不修改）→ 通知 PM。
 *  fail  → 项目退 ACTIVE，检查单解锁回 DRAFT → 通知 PM（含驳回意见）。
 * 只注入 DAO + JdbcTemplate + NoticeService（不依赖业务服务，避免审批引擎构造循环）。
 */
@Slf4j
@Component
public class PmClosureApprovalCallback implements ApprovalCallback {

    public static final String BIZ_CLOSURE = PmClosureServiceImpl.BIZ_CLOSURE;
    static final int REMEDIATION_DAYS = PmClosureServiceImpl.REMEDIATION_DAYS;

    private final ClosureCheckDao checkDao;
    private final ClosureCheckItemDao itemDao;
    private final ClosureRemediationDao remediationDao;
    private final PmProjectDao projectDao;
    private final JdbcTemplate jdbcTemplate;
    private final NoticeService noticeService;

    public PmClosureApprovalCallback(ClosureCheckDao checkDao,
                                     ClosureCheckItemDao itemDao,
                                     ClosureRemediationDao remediationDao,
                                     PmProjectDao projectDao,
                                     JdbcTemplate jdbcTemplate,
                                     NoticeService noticeService) {
        this.checkDao = checkDao;
        this.itemDao = itemDao;
        this.remediationDao = remediationDao;
        this.projectDao = projectDao;
        this.jdbcTemplate = jdbcTemplate;
        this.noticeService = noticeService;
    }

    @Override
    public Set<String> supportedBizTypes() {
        return Set.of(BIZ_CLOSURE);
    }

    @Override
    @Transactional
    public void onApproved(ApprovalInstance instance) {
        ClosureCheck c = checkOfProject(instance.getBizId());
        if (c == null) {
            log.warn("closure approved but check missing: {}", instance.getBizId());
            return;
        }
        PmProject p = projectDao.selectById(c.getProjectId());

        // ① 有条件结项：DRAFT 遗留项转 OPEN（+90 天）
        List<ClosureRemediation> drafts = remediationDao.selectList(
                new LambdaQueryWrapper<ClosureRemediation>()
                        .eq(ClosureRemediation::getCheckId, c.getId())
                        .eq(ClosureRemediation::getStatus, ClosureRemediation.ST_DRAFT)
                        .eq(ClosureRemediation::getDelFlag, "0"));
        for (ClosureRemediation r : drafts) {
            r.setStatus(ClosureRemediation.ST_OPEN);
            r.setDueDate(LocalDateTime.now().plusDays(REMEDIATION_DAYS));
            r.setUpdateBy("system");
            r.setUpdateDate(LocalDateTime.now());
            remediationDao.updateById(r);
        }
        c.setConditionalFlag(drafts.isEmpty() ? "0" : "1");
        c.setAuditOpinion(lastPassOpinion(instance.getId()));

        // ② 项目置 CLOSED（归档终态）
        if (p != null) {
            p.setStatus(PmProject.ST_CLOSED);
            p.setUpdateBy("system");
            p.setUpdateDate(LocalDateTime.now());
            projectDao.updateById(p);
        }

        // ③ 资源释放清单快照（参与人/工时汇总；capacity 通用日历不修改）
        c.setResReleaseJson(releaseSnapshot(c.getProjectId()));
        c.setUpdateBy("system");
        c.setUpdateDate(LocalDateTime.now());
        checkDao.updateById(c);

        try {
            noticeService.push(null, p == null ? null : p.getPmUserId(),
                    "项目已归档：" + (p == null ? c.getProjectNo() : p.getName()),
                    "结项审批已通过，项目进入归档态（CLOSED），全域只读。"
                            + (drafts.isEmpty() ? "" : "含 " + drafts.size()
                            + " 项有条件遗留跟踪（90 天内闭环）。"),
                    BIZ_CLOSURE, c.getId());
        } catch (Exception e) {
            log.warn("closure approve notice failed: {}", e.getMessage());
        }
        log.info("closure approved -> CLOSED: {} remediations={}", c.getId(), drafts.size());
    }

    @Override
    @Transactional
    public void onRejected(ApprovalInstance instance) {
        ClosureCheck c = checkOfProject(instance.getBizId());
        if (c == null) {
            log.warn("closure rejected but check missing: {}", instance.getBizId());
            return;
        }
        PmProject p = projectDao.selectById(c.getProjectId());
        String opinion = lastRejectOpinion(instance.getId());
        // 检查单解锁回 DRAFT（PM 可修正后重新提交；自动项可重算）
        c.setStatus(ClosureCheck.ST_DRAFT);
        c.setApprovalId(null);
        c.setAuditOpinion(opinion);
        c.setUpdateBy("system");
        c.setUpdateDate(LocalDateTime.now());
        checkDao.updateById(c);
        if (p != null) {
            p.setStatus(PmProject.ST_ACTIVE);
            p.setUpdateBy("system");
            p.setUpdateDate(LocalDateTime.now());
            projectDao.updateById(p);
        }
        try {
            noticeService.push(null, p == null ? null : p.getPmUserId(),
                    "结项审批驳回：" + (p == null ? c.getProjectNo() : p.getName()),
                    "结项申请被驳回：" + opinion + "。项目回到执行中，请修正检查单后重新提交。",
                    BIZ_CLOSURE, c.getId());
        } catch (Exception e) {
            log.warn("closure reject notice failed: {}", e.getMessage());
        }
        log.info("closure rejected -> ACTIVE: {}", c.getId());
    }

    /** bizId=projectId（submitForApproval 口径）→ 检查单 */
    private ClosureCheck checkOfProject(String projectId) {
        return checkDao.selectOne(new LambdaQueryWrapper<ClosureCheck>()
                .eq(ClosureCheck::getProjectId, projectId)
                .eq(ClosureCheck::getDelFlag, "0")
                .last("LIMIT 1"));
    }

    /** 资源释放清单：按参与人汇总项目工时（取自工时行），JSON 落检查单头 */
    private String releaseSnapshot(String projectId) {
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT t.EMP_ID AS empId, t.EMP_NAME AS empName, "
                        + "COALESCE(SUM(l.HOURS),0) AS hours "
                        + "FROM erp_pm_timesheet_line l "
                        + "JOIN erp_pm_timesheet t ON t.ID = l.TIMESHEET_ID "
                        + "WHERE l.PROJECT_ID=? AND l.DEL_FLAG='0' AND t.DEL_FLAG='0' "
                        + "GROUP BY t.EMP_ID, t.EMP_NAME ORDER BY hours DESC", projectId);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (java.util.Map<String, Object> r : rows) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(String.format("{\"empId\":\"%s\",\"empName\":\"%s\",\"hours\":%s}",
                    r.get("empId"), r.get("empName"), r.get("hours")));
        }
        return sb.append(']').toString();
    }

    private String lastPassOpinion(String apprId) {
        List<String> tasks = jdbcTemplate.queryForList(
                "SELECT OPINION FROM erp_sys_approval_task WHERE APPR_ID=? "
                        + "AND STATUS='PASSED' ORDER BY OP_TIME", String.class, apprId);
        return tasks.isEmpty() ? null : tasks.get(tasks.size() - 1);
    }

    private String lastRejectOpinion(String apprId) {
        List<String> tasks = jdbcTemplate.queryForList(
                "SELECT OPINION FROM erp_sys_approval_task WHERE APPR_ID=? "
                        + "AND STATUS='REJECTED' ORDER BY OP_TIME", String.class, apprId);
        return tasks.isEmpty() ? null : tasks.get(tasks.size() - 1);
    }
}
