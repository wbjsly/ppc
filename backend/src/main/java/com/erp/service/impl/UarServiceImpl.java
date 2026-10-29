package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.UarItemDao;
import com.erp.dao.system.UarReportDao;
import com.erp.dao.system.UarTaskDao;
import com.erp.entity.system.GrantLedger;
import com.erp.dao.system.GrantLedgerDao;
import com.erp.entity.system.UarItem;
import com.erp.entity.system.UarReport;
import com.erp.entity.system.UarTask;
import com.erp.service.GrantLedgerService;
import com.erp.service.UarService;
import com.erp.service.system.PositionBaselineService;
import com.erp.service.system.PrivilegedRoles;
import com.erp.service.system.SensitiveRoles;
import com.erp.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 季度最小权限复核实现（add-access-review-console design D1-D7，SOP-5.6-D）。
 * 判定不走审批引擎（清单打勾语义）；复核人二态校验在本服务内完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UarServiceImpl implements UarService {

    private final UarTaskDao taskDao;
    private final UarItemDao itemDao;
    private final UarReportDao reportDao;
    private final GrantLedgerDao ledgerDao;
    private final GrantLedgerService grantLedgerService;
    private final PositionBaselineService baselineService;
    private final JdbcTemplate jdbc;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int REVIEW_DAYS = 10; // 完成时限（工作日口径，自然日近似 +14 天）

    // ---------- 生成 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generate() {
        // 同周期（当前自然季）未完成任务幂等拒绝
        String cycle = cycleTag();
        Long exist = taskDao.selectCount(new LambdaQueryWrapper<UarTask>()
                .eq(UarTask::getCycleTag, cycle)
                .in(UarTask::getStatus, UarTask.ST_REVIEWING, UarTask.ST_COMPLETED));
        if (exist != null && exist > 0) {
            throw new ServiceException(422, "本周期已有未完成的复核任务：" + cycle);
        }

        UarTask task = new UarTask();
        task.setId(uuid());
        task.setCycleTag(cycle);
        // 10 工作日 ≈ 14 自然日（design：自然日近似口径）
        task.setDueAt(LocalDateTime.now().plusDays(14));
        task.setStatus(UarTask.ST_REVIEWING);
        task.setCreateBy(operator());
        taskDao.insert(task);

        // 全部活跃账号（STATUS='1'）
        List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT u.ID AS uid, u.USERNAME AS uname, e.POS_ID AS posId, "
                        + "e.REPORT_TO_EMP_ID AS reportTo, u.STATUS AS status "
                        + "FROM erp_admin_user u "
                        + "LEFT JOIN hr_employee e ON e.USER_ID = u.ID AND e.DEL_FLAG = '0' "
                        + "WHERE u.DEL_FLAG = '0' AND u.STATUS = '1'");

        int human = 0;
        int autoPass = 0;
        for (Map<String, Object> acc : accounts) {
            String uid = String.valueOf(acc.get("uid"));
            List<String> roles = currentRoles(uid);
            if (roles.isEmpty()) {
                autoPass++;
                continue;
            }
            List<String> baseline = baselineOf(acc.get("posId"));
            boolean privileged = roles.stream().anyMatch(PrivilegedRoles::contains);
            boolean sensitive = roles.stream().anyMatch(SensitiveRoles::contains);
            List<String> beyond = roles.stream().filter(r -> !baseline.contains(r)).toList();
            boolean offboarded = "0".equals(String.valueOf(acc.get("status"))); // 活跃查询恒 false，保留口径
            // 风险分层：特权/敏感 100% 人工；普通仅基线偏离；基线内自动通过
            if (!privileged && !sensitive && beyond.isEmpty()) {
                autoPass++;
                continue;
            }
            // 使用统计（hr_change_log 账号级，决策甲）
            Map<String, Object> usage = usageOf(uid);
            long opCount = ((Number) usage.get("cnt")).longValue();
            boolean unused = opCount == 0;
            // 快照（生成时刻固化）
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("roles", roles);
            snap.put("baseline", baseline);
            snap.put("beyondBaseline", beyond);
            snap.put("opCount90d", opCount);
            snap.put("lastOpAt", usage.get("lastAt"));
            snap.put("anomalies", anomalies(unused, !beyond.isEmpty(), offboarded));
            String snapJson;
            try {
                snapJson = MAPPER.writeValueAsString(snap);
            } catch (Exception e) {
                throw new ServiceException(500, "快照序列化失败");
            }
            // 复核人推导（D4：REPORT_TO 按人 → DEPT_HEAD → ADMIN 兜底）
            String reviewerUser = null;
            String reviewerRole = null;
            String reportTo = acc.get("reportTo") == null ? null : String.valueOf(acc.get("reportTo"));
            if (StringUtils.hasText(reportTo)) {
                String mgrUser = jdbc.queryForObject(
                        "SELECT USER_ID FROM hr_employee WHERE ID=? AND DEL_FLAG='0' "
                                + "AND USER_ID IS NOT NULL AND USER_ID <> '' LIMIT 1",
                        String.class, reportTo);
                if (StringUtils.hasText(mgrUser)) {
                    reviewerUser = mgrUser;
                } else {
                    reviewerRole = "ROLE_DEPT_HEAD";
                }
            } else {
                reviewerRole = "ROLE_ADMIN";
            }
            // item 按账号×角色展开
            for (String role : roles) {
                UarItem item = new UarItem();
                item.setId(uuid());
                item.setTaskId(task.getId());
                item.setTargetUserId(uid);
                item.setRoleCode(role);
                item.setRiskTag(PrivilegedRoles.contains(role) ? "PRIVILEGED"
                        : SensitiveRoles.contains(role) ? "SENSITIVE" : "NORMAL");
                item.setSnapshotJson(snapJson);
                item.setReviewerUserId(reviewerUser);
                item.setReviewerRole(reviewerRole);
                item.setExecStatus(UarItem.EX_PENDING);
                item.setCreateBy(operator());
                itemDao.insert(item);
                human++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getId());
        out.put("cycleTag", cycle);
        out.put("dueAt", task.getDueAt());
        out.put("humanItems", human);
        out.put("autoPassedAccounts", autoPass);
        return out;
    }

    // ---------- 列表 ----------

    @Override
    public List<Map<String, Object>> taskList() {
        return jdbc.queryForList(
                "SELECT t.ID AS id, t.CYCLE_TAG AS cycleTag, t.DUE_AT AS dueAt, t.STATUS AS status, "
                        + "t.ESCALATED_AT AS escalatedAt, t.CREATE_DATE AS createDate, "
                        + "(SELECT COUNT(*) FROM erp_admin_uar_item i WHERE i.TASK_ID=t.ID "
                        + "  AND i.DEL_FLAG='0') AS totalItems, "
                        + "(SELECT COUNT(*) FROM erp_admin_uar_item i WHERE i.TASK_ID=t.ID "
                        + "  AND i.DEL_FLAG='0' AND i.JUDGMENT IS NOT NULL) AS judgedItems, "
                        + "(SELECT COUNT(*) FROM erp_admin_uar_item i WHERE i.TASK_ID=t.ID "
                        + "  AND i.DEL_FLAG='0' AND i.JUDGMENT='RECLAIM') AS reclaimItems "
                        + "FROM erp_admin_uar_task t WHERE t.DEL_FLAG='0' "
                        + "ORDER BY t.CREATE_DATE DESC");
    }

    @Override
    public List<Map<String, Object>> itemList(String taskId, boolean mineOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT i.ID AS id, i.TASK_ID AS taskId, i.TARGET_USER_ID AS targetUserId, "
                        + "u.USERNAME AS targetUsername, i.ROLE_CODE AS roleCode, i.RISK_TAG AS riskTag, "
                        + "i.SNAPSHOT_JSON AS snapshotJson, i.REVIEWER_USER_ID AS reviewerUserId, "
                        + "i.REVIEWER_ROLE AS reviewerRole, i.JUDGMENT AS judgment, "
                        + "i.JUDGE_REASON AS judgeReason, i.JUDGED_AT AS judgedAt, "
                        + "i.EXEC_STATUS AS execStatus "
                        + "FROM erp_admin_uar_item i "
                        + "LEFT JOIN erp_admin_user u ON u.ID = i.TARGET_USER_ID "
                        + "WHERE i.DEL_FLAG='0' AND i.TASK_ID=? ");
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        if (mineOnly) {
            sql.append("AND (i.REVIEWER_USER_ID = ? OR i.REVIEWER_ROLE IN "
                    + "(SELECT r.ROLE_CODE FROM erp_admin_role r JOIN erp_admin_user_role ur "
                    + " ON ur.ROLE_ID=r.ID WHERE ur.USER_ID=?)) ");
            String me = operator();
            args.add(me);
            args.add(me);
        }
        sql.append("ORDER BY i.TARGET_USER_ID, i.ROLE_CODE");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    // ---------- 判定 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> judge(String itemId, String judgment, String reason) {
        if (!UarItem.J_KEEP.equals(judgment) && !UarItem.J_RECLAIM.equals(judgment)) {
            throw new ServiceException(422, "判定仅支持 KEEP/RECLAIM");
        }
        UarItem item = itemDao.selectById(itemId);
        if (item == null) {
            throw new ServiceException(404, "复核项不存在：" + itemId);
        }
        if (StringUtils.hasText(item.getJudgment())) {
            throw new ServiceException(422, "该项已判定，不可重复");
        }
        // 越权 403（D4 二态校验）
        String me = operator();
        boolean authorized;
        if (StringUtils.hasText(item.getReviewerUserId())) {
            authorized = me.equals(item.getReviewerUserId());
        } else {
            authorized = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_admin_user_role ur JOIN erp_admin_role r "
                            + "ON r.ID=ur.ROLE_ID WHERE ur.USER_ID=? AND r.ROLE_CODE=?",
                    Integer.class, me, item.getReviewerRole()) > 0;
        }
        if (!authorized) {
            throw new ServiceException(403, "非本项复核人，无权判定");
        }
        // 保留长期未使用/超基线角色 → 理由 ≥10 字（L1）
        if (UarItem.J_KEEP.equals(judgment)) {
            Map<String, Object> snap = parse(item.getSnapshotJson());
            @SuppressWarnings("unchecked")
            List<String> anomalies = (List<String>) snap.getOrDefault("anomalies", List.of());
            boolean needsReason = anomalies.contains("UNUSED") || anomalies.contains("BEYOND_BASELINE");
            if (needsReason && (!StringUtils.hasText(reason) || reason.trim().length() < 10)) {
                throw new ServiceException(422,
                        "保留长期未使用或超基线角色须填写业务理由（≥10 字），请补全后重试");
            }
        }
        item.setJudgment(judgment);
        item.setJudgeReason(StringUtils.hasText(reason) ? reason.trim() : null);
        item.setJudgedAt(LocalDateTime.now());
        item.setExecStatus(UarItem.J_RECLAIM.equals(judgment) ? UarItem.EX_PENDING : UarItem.EX_SKIPPED);
        item.setUpdateBy(me);
        item.setUpdateDate(LocalDateTime.now());
        itemDao.updateById(item);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", item.getId());
        out.put("judgment", judgment);
        out.put("progress", progressOf(item.getTaskId()));
        return out;
    }

    // ---------- 回收执行 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> executeReclaim(String itemId) {
        UarItem item = itemDao.selectById(itemId);
        if (item == null) {
            throw new ServiceException(404, "复核项不存在：" + itemId);
        }
        if (!UarItem.J_RECLAIM.equals(item.getJudgment())) {
            throw new ServiceException(422, "仅判定为收回的项可执行回收");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", itemId);
        if (UarItem.EX_EXECUTED.equals(item.getExecStatus())) {
            out.put("executed", false); // 幂等
            return out;
        }
        String me = operator();
        // 有台账 ACTIVE 行 → reclaimForReview（绕守卫，D5）；无 → 直删投影
        List<GrantLedger> ledgers = ledgerDao.selectList(new LambdaQueryWrapper<GrantLedger>()
                .eq(GrantLedger::getTargetUserId, item.getTargetUserId())
                .eq(GrantLedger::getRoleCode, item.getRoleCode())
                .eq(GrantLedger::getStatus, GrantLedger.ST_ACTIVE));
        if (!ledgers.isEmpty()) {
            for (GrantLedger led : ledgers) {
                grantLedgerService.reclaimForReview(led.getId(), me);
            }
        } else {
            jdbc.update("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                            + "WHERE ur.USER_ID=? AND r.ROLE_CODE=?",
                    item.getTargetUserId(), item.getRoleCode());
        }
        // 审计 + 通知
        jdbc.update("INSERT INTO hr_change_log (ID, EMP_ID, FIELD_NAME, OLD_VALUE, NEW_VALUE, "
                        + "REASON, CHANGE_BY, CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                uuid(), item.getTargetUserId(), "UAR_RECLAIMED", item.getRoleCode(), "",
                "UAR 复核收回 任务 " + item.getTaskId(), me, LocalDateTime.now());
        notice(item.getTargetUserId(), "权限复核收回通知",
                "角色 " + item.getRoleCode() + " 经季度权限复核判定收回并已执行，如有业务需要请重新提交权限申请",
                item.getTaskId(), me);
        if (StringUtils.hasText(item.getReviewerUserId()) && !item.getReviewerUserId().equals(me)) {
            notice(item.getReviewerUserId(), "复核收回执行完成",
                    "任务 " + item.getTaskId() + " 项（账号 " + item.getTargetUserId()
                            + " 角色 " + item.getRoleCode() + "）已执行收回", item.getTaskId(), me);
        }
        item.setExecStatus(UarItem.EX_EXECUTED);
        item.setUpdateBy(me);
        item.setUpdateDate(LocalDateTime.now());
        itemDao.updateById(item);
        out.put("executed", true);
        return out;
    }

    // ---------- 报告 ----------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> summarize(String taskId) {
        UarTask task = taskDao.selectById(taskId);
        if (task == null) {
            throw new ServiceException(404, "任务不存在：" + taskId);
        }
        if (UarTask.ST_ARCHIVED.equals(task.getStatus())) {
            throw new ServiceException(422, "任务已归档，不可重复汇总");
        }
        Map<String, Object> cnt = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
                        + "COALESCE(SUM(JUDGMENT IS NOT NULL),0) AS judged, "
                        + "COALESCE(SUM(JUDGMENT='RECLAIM'),0) AS reclaimed "
                        + "FROM erp_admin_uar_item WHERE TASK_ID=? AND DEL_FLAG='0'", taskId);
        int total = ((Number) cnt.get("total")).intValue();
        int judged = ((Number) cnt.get("judged")).intValue();
        int reclaimed = ((Number) cnt.get("reclaimed")).intValue();
        BigDecimal coverage = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(judged * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
        BigDecimal reclaimRate = judged == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(reclaimed * 100.0 / judged).setScale(2, RoundingMode.HALF_UP);
        // 三类异常清单（聚合计数 + 紧凑摘要，防超列长；明细可回快照页查）
        Integer unusedCnt = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT TARGET_USER_ID) FROM erp_admin_uar_item "
                        + "WHERE TASK_ID=? AND DEL_FLAG='0' AND SNAPSHOT_JSON LIKE '%\"UNUSED\"%'",
                Integer.class, taskId);
        Integer beyondCnt = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT TARGET_USER_ID) FROM erp_admin_uar_item "
                        + "WHERE TASK_ID=? AND DEL_FLAG='0' AND SNAPSHOT_JSON LIKE '%\"BEYOND_BASELINE\"%'",
                Integer.class, taskId);
        Integer offboardCnt = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT TARGET_USER_ID) FROM erp_admin_uar_item "
                        + "WHERE TASK_ID=? AND DEL_FLAG='0' AND SNAPSHOT_JSON LIKE '%\"OFFBOARDED\"%'",
                Integer.class, taskId);
        Map<String, Object> anomalies = new LinkedHashMap<>();
        anomalies.put("unusedAccounts", unusedCnt == null ? 0 : unusedCnt);
        anomalies.put("beyondBaselineAccounts", beyondCnt == null ? 0 : beyondCnt);
        anomalies.put("offboardedAccounts", offboardCnt == null ? 0 : offboardCnt);
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT CONCAT(TARGET_USER_ID, ':', ROLE_CODE) AS k "
                        + "FROM erp_admin_uar_item WHERE TASK_ID=? AND DEL_FLAG='0' "
                        + "AND JUDGMENT IS NULL LIMIT 50", taskId);
        Integer pendingTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_uar_item WHERE TASK_ID=? AND DEL_FLAG='0' "
                        + "AND JUDGMENT IS NULL", Integer.class, taskId);

        UarReport report = reportDao.selectOne(new LambdaQueryWrapper<UarReport>().eq(UarReport::getTaskId, taskId).last("LIMIT 1"));
        boolean isNew = report == null;
        if (isNew) {
            report = new UarReport();
            report.setId(uuid());
            report.setTaskId(taskId);
            report.setCreateBy(operator());
        }
        report.setTotalItems(total);
        report.setJudgedItems(judged);
        report.setCoverageRate(coverage);
        report.setReclaimedItems(reclaimed);
        report.setReclaimRate(reclaimRate);
        try {
            report.setAnomalyJson(MAPPER.writeValueAsString(anomalies));
            Map<String, Object> pendingOut = new LinkedHashMap<>();
            pendingOut.put("total", pendingTotal == null ? 0 : pendingTotal);
            pendingOut.put("sample", pending);
            report.setPendingJson(MAPPER.writeValueAsString(pendingOut));
        } catch (Exception e) {
            throw new ServiceException(500, "清单序列化失败");
        }
        if (isNew) {
            reportDao.insert(report);
        } else {
            reportDao.updateById(report);
        }
        // 全部判定完成 → 任务置 COMPLETED（待双签归档）
        if (judged == total && total > 0 && UarTask.ST_REVIEWING.equals(task.getStatus())) {
            task.setStatus(UarTask.ST_COMPLETED);
            task.setUpdateBy(operator());
            task.setUpdateDate(LocalDateTime.now());
            taskDao.updateById(task);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("totalItems", total);
        out.put("judgedItems", judged);
        out.put("coverageRate", coverage);
        out.put("reclaimedItems", reclaimed);
        out.put("reclaimRate", reclaimRate);
        out.put("pendingCount", pending.size());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReport(String taskId) {
        UarReport report = requireReport(taskId);
        if (report.getConfirmAt() != null) {
            throw new ServiceException(422, "报告已确认，不可重复");
        }
        report.setConfirmBy(operator());
        report.setConfirmAt(LocalDateTime.now());
        report.setUpdateBy(operator());
        report.setUpdateDate(LocalDateTime.now());
        reportDao.updateById(report);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void signReport(String taskId) {
        UarReport report = requireReport(taskId);
        if (report.getSignAt() != null) {
            throw new ServiceException(422, "报告已会签，不可重复");
        }
        if (report.getConfirmAt() == null) {
            throw new ServiceException(422, "报告未经 ADMIN 确认，不可会签（C-5.6-05 双签顺序）");
        }
        report.setSignBy(operator());
        report.setSignAt(LocalDateTime.now());
        report.setArchived("1");
        report.setUpdateBy(operator());
        report.setUpdateDate(LocalDateTime.now());
        reportDao.updateById(report);
        // 任务归档
        UarTask task = taskDao.selectById(taskId);
        if (task != null) {
            task.setStatus(UarTask.ST_ARCHIVED);
            task.setUpdateBy(operator());
            task.setUpdateDate(LocalDateTime.now());
            taskDao.updateById(task);
        }
    }

    @Override
    public List<Map<String, Object>> reportList() {
        return jdbc.queryForList(
                "SELECT r.ID AS id, r.TASK_ID AS taskId, t.CYCLE_TAG AS cycleTag, "
                        + "r.TOTAL_ITEMS AS totalItems, r.JUDGED_ITEMS AS judgedItems, "
                        + "r.COVERAGE_RATE AS coverageRate, r.RECLAIMED_ITEMS AS reclaimedItems, "
                        + "r.RECLAIM_RATE AS reclaimRate, r.CONFIRM_BY AS confirmBy, "
                        + "r.CONFIRM_AT AS confirmAt, r.SIGN_BY AS signBy, r.SIGN_AT AS signAt, "
                        + "r.ARCHIVED AS archived, t.STATUS AS taskStatus "
                        + "FROM erp_admin_uar_report r "
                        + "JOIN erp_admin_uar_task t ON t.ID = r.TASK_ID "
                        + "WHERE r.DEL_FLAG='0' ORDER BY r.CREATE_DATE DESC");
    }

    // ---------- 逾期升级 ----------

    @Override
    public Map<String, Object> sweepOverdue() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT ID, CYCLE_TAG, DUE_AT, ESCALATED_AT, REMIND_AT FROM erp_admin_uar_task "
                        + "WHERE DEL_FLAG='0' AND STATUS='REVIEWING' AND DUE_AT <= ? "
                        + "AND (ESCALATED_AT IS NULL OR REMIND_AT <= ?)",
                now.plusDays(2), now.minusHours(12));
        int escalated = 0;
        int reminded = 0;
        for (Map<String, Object> row : due) {
            String taskId = String.valueOf(row.get("ID"));
            Map<String, Object> prog = progressOf(taskId);
            int total = ((Number) prog.get("totalItems")).intValue();
            int judged = ((Number) prog.get("judgedItems")).intValue();
            if (total == 0 || judged >= total) {
                continue; // 完成率 100% 不升级
            }
            boolean first = row.get("ESCALATED_AT") == null;
            jdbc.update("UPDATE erp_admin_uar_task SET ESCALATED_AT=COALESCE(ESCALATED_AT,NOW()), "
                    + "REMIND_AT=NOW(), UPDATE_DATE=NOW() WHERE ID=?", taskId);
            String content = "季度权限复核任务 " + row.get("CYCLE_TAG") + " 距截止（" + row.get("DUE_AT")
                    + "）不足 2 个工作日，完成率 " + judged + "/" + total
                    + "，请限期完成剩余复核（BR-5.6-10，逾期项将单独纳入报告）";
            for (String u : roleHolders("ROLE_DEPT_HEAD")) {
                notice(u, "UAR 复核逾期升级", content, taskId, "uar-sweep");
            }
            for (String u : roleHolders("ROLE_UAR_AUDITOR")) {
                notice(u, "UAR 复核逾期抄送", content, taskId, "uar-sweep");
            }
            if (first) {
                escalated++;
            } else {
                reminded++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("escalated", escalated);
        out.put("reminded", reminded);
        return out;
    }

    // ---------- 工具 ----------

    private UarReport requireReport(String taskId) {
        UarReport report = reportDao.selectOne(new LambdaQueryWrapper<UarReport>().eq(UarReport::getTaskId, taskId).last("LIMIT 1"));
        if (report == null) {
            throw new ServiceException(404, "报告不存在（尚未汇总）：" + taskId);
        }
        return report;
    }

    private Map<String, Object> progressOf(String taskId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS totalItems, "
                        + "COALESCE(SUM(JUDGMENT IS NOT NULL),0) AS judgedItems "
                        + "FROM erp_admin_uar_item WHERE TASK_ID=? AND DEL_FLAG='0'", taskId);
        return row;
    }

    private List<String> currentRoles(String userId) {
        return jdbc.queryForList(
                "SELECT r.ROLE_CODE FROM erp_admin_user_role ur "
                        + "JOIN erp_admin_role r ON r.ID = ur.ROLE_ID WHERE ur.USER_ID = ?",
                String.class, userId);
    }

    private List<String> baselineOf(Object posId) {
        if (posId == null || !StringUtils.hasText(String.valueOf(posId))) {
            return List.of();
        }
        return baselineService.rolesOfPos(String.valueOf(posId));
    }

    private Map<String, Object> usageOf(String userId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS cnt, MAX(CHANGE_AT) AS lastAt FROM hr_change_log "
                        + "WHERE EMP_ID = ? AND CHANGE_AT >= ?", userId, LocalDateTime.now().minusDays(90));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cnt", row.get("cnt") == null ? 0 : row.get("cnt"));
        out.put("lastAt", row.get("lastAt"));
        return out;
    }

    private List<String> anomalies(boolean unused, boolean beyond, boolean offboarded) {
        List<String> out = new ArrayList<>();
        if (unused) {
            out.add("UNUSED");
        }
        if (beyond) {
            out.add("BEYOND_BASELINE");
        }
        if (offboarded) {
            out.add("OFFBOARDED");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> roleHolders(String roleCode) {
        return jdbc.queryForList(
                "SELECT u.ID FROM erp_admin_user u JOIN erp_admin_user_role ur ON ur.USER_ID = u.ID "
                        + "JOIN erp_admin_role r ON r.ID = ur.ROLE_ID "
                        + "WHERE r.ROLE_CODE = ? AND u.STATUS = '1'", String.class, roleCode);
    }

    private void notice(String userId, String title, String content, String bizId, String operator) {
        try {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, "
                            + "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?,?,?,?,?,?, '0', ?, NOW(), '0', 0)",
                    uuid(), userId, title, content, "UarReview", bizId, operator);
        } catch (Exception e) {
            log.warn("uar notice write failed: {}", e.getMessage());
        }
    }

    private String cycleTag() {
        LocalDateTime now = LocalDateTime.now();
        int q = (now.getMonthValue() - 1) / 3 + 1;
        return now.getYear() + "Q" + q;
    }

    private String operator() {
        String me = SecurityUtils.getCurrentUserId();
        return StringUtils.hasText(me) ? me : "uar-system";
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
