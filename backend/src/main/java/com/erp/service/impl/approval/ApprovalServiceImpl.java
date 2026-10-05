package com.erp.service.impl.approval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.system.ApprovalInstance;
import com.erp.entity.system.ApprovalTask;
import com.erp.entity.system.SysUser;
import com.erp.service.approval.ApprovalCallback;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 通用审批底座实现（design D4）。
 * 并发签署：节点行乐观锁（VER_NO），updateById 返回 0 → 422「并发签署，请刷新重试」。
 * 超时：以节点 UPDATE_DATE 为激活时间（PENDING→ACTIVE 的更新会刷新该列）。
 
 *
 * <p>偏差 D6（add-quality-collaboration）——本底座与既有 PR/PO/招标/收货调整审批双轨并存，旧审批不迁移；
 * 后续统一变更中归并。*/
@Slf4j
@Service
public class ApprovalServiceImpl implements ApprovalEngine {

    private final ApprovalInstanceDao instanceDao;
    private final ApprovalTaskDao taskDao;
    private final SysUserDao sysUserDao;
    private final List<ApprovalCallback> callbacks;
    private final Map<String, ApprovalCallback> callbackMap = new HashMap<>();

    @Value("${app.qms.approval-remind-hours:72}")
    private int remindHours;

    @Value("${app.qms.approval-escalate-days:7}")
    private int escalateDays;

    public ApprovalServiceImpl(ApprovalInstanceDao instanceDao,
                               ApprovalTaskDao taskDao,
                               SysUserDao sysUserDao,
                               List<ApprovalCallback> callbacks) {
        this.instanceDao = instanceDao;
        this.taskDao = taskDao;
        this.sysUserDao = sysUserDao;
        this.callbacks = callbacks;
        for (ApprovalCallback cb : callbacks) {
            for (String type : cb.supportedBizTypes()) {
                callbackMap.put(type, cb);
            }
        }
    }

    // ---------- 提交 ----------

    @Override
    @Transactional
    public ApprovalInstance submit(String bizType, String bizId, String title,
                                   String escalateTo, List<List<ApprovalNodeSpec>> seqGroups) {
        if (!hasText(bizType) || !hasText(bizId)) {
            throw new ServiceException(422, "业务类型与单据 ID 必填");
        }
        if (seqGroups == null || seqGroups.isEmpty()) {
            throw new ServiceException(422, "审批链不能为空");
        }
        long pending = instanceDao.selectCount(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getBizType, bizType)
                .eq(ApprovalInstance::getBizId, bizId)
                .eq(ApprovalInstance::getStatus, "PENDING"));
        if (pending > 0) {
            throw new ServiceException(422, "该单据审批中，不可重复提交");
        }

        ApprovalInstance inst = new ApprovalInstance();
        inst.setApprNo(nextApprNo());
        inst.setBizType(bizType);
        inst.setBizId(bizId);
        inst.setTitle(title);
        inst.setStatus("PENDING");
        inst.setEscalateTo(hasText(escalateTo) ? escalateTo : null);
        inst.setApplyBy(SecurityUtils.getCurrentUserId());
        inst.setApplyDate(LocalDateTime.now());
        instanceDao.insert(inst);

        int seqNo = 1;
        for (List<ApprovalNodeSpec> group : seqGroups) {
            if (group == null || group.isEmpty()) {
                throw new ServiceException(422, "审批链存在空节点组");
            }
            for (ApprovalNodeSpec spec : group) {
                if (!hasText(spec.getRoleRequired())) {
                    throw new ServiceException(422, "审批节点缺少角色");
                }
                ApprovalTask task = new ApprovalTask();
                task.setApprId(inst.getId());
                task.setSeq(seqNo);
                task.setNodeType(spec.getNodeType());
                task.setRoleRequired(spec.getRoleRequired());
                task.setNodeName(spec.getNodeName());
                // 首个 SEQ 立即激活，其余等前置 SEQ 全过后激活（UPDATE_DATE 随之刷新）
                task.setStatus(seqNo == 1 ? "ACTIVE" : "PENDING");
                task.setRemindCount(0);
                task.setEscalated("0");
                taskDao.insert(task);
            }
            seqNo++;
        }
        return inst;
    }

    // ---------- 签署 ----------

    @Override
    @Transactional
    public ApprovalInstance pass(String taskId, String opinion) {
        ApprovalTask task = requireTask(taskId);
        ApprovalInstance inst = requireInstance(task.getApprId());
        requireSignable(task, inst);
        requireNodeRole(task);

        task.setStatus("PASSED");
        task.setSigner(SecurityUtils.getCurrentUserId());
        task.setSignerName(displayName(SecurityUtils.getCurrentUserId()));
        task.setOpinion(hasText(opinion) ? opinion : "同意");
        task.setOpTime(LocalDateTime.now());
        if (taskDao.updateById(task) == 0) {
            throw new ServiceException(422, "并发签署冲突，请刷新后重试");
        }

        // 同 SEQ 其余签署节点是否全过
        List<ApprovalTask> siblings = sameSeqSignNodes(task);
        boolean allPassed = siblings.stream().allMatch(t -> "PASSED".equals(t.getStatus()));
        if (!allPassed) {
            return inst; // 并行双签：等待另一签（C-4.12-01 缺一不可）
        }
        return advance(inst, task.getSeq());
    }

    @Override
    @Transactional
    public ApprovalInstance reject(String taskId, String opinion) {
        if (!hasText(opinion) || opinion.trim().length() < 2) {
            throw new ServiceException(422, "驳回意见必填（至少 2 字）");
        }
        ApprovalTask task = requireTask(taskId);
        ApprovalInstance inst = requireInstance(task.getApprId());
        requireSignable(task, inst);
        requireNodeRole(task);

        task.setStatus("REJECTED");
        task.setSigner(SecurityUtils.getCurrentUserId());
        task.setSignerName(displayName(SecurityUtils.getCurrentUserId()));
        task.setOpinion(opinion);
        task.setOpTime(LocalDateTime.now());
        if (taskDao.updateById(task) == 0) {
            throw new ServiceException(422, "并发签署冲突，请刷新后重试");
        }

        // 同单其余未办结节点（PENDING 未激活 / ACTIVE 同签并行）置 SKIPPED（保留已签记录）
        List<ApprovalTask> rest = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, inst.getId())
                .in(ApprovalTask::getStatus, Arrays.asList("PENDING", "ACTIVE")));
        for (ApprovalTask t : rest) {
            if (t.getId().equals(task.getId())) {
                continue; // 被驳回节点保持 REJECTED
            }
            t.setStatus("SKIPPED");
            taskDao.updateById(t);
        }
        finish(inst, "REJECTED");
        fireCallback(inst, false);
        return inst;
    }

    // ---------- 查询 ----------

    @Override
    public ApprovalInstance getInstance(String id) {
        ApprovalInstance inst = instanceDao.selectById(id);
        if (inst == null) {
            throw new ServiceException(404, "审批实例不存在");
        }
        return inst;
    }

    @Override
    public ApprovalInstance findByBiz(String bizType, String bizId) {
        return instanceDao.selectOne(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getBizType, bizType)
                .eq(ApprovalInstance::getBizId, bizId)
                .orderByDesc(ApprovalInstance::getCreateDate)
                .last("LIMIT 1"));
    }

    @Override
    public List<Map<String, Object>> todo() {
        List<String> roles = currentRoles();
        if (roles.isEmpty()) {
            return List.of();
        }
        boolean isAdmin = roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r));

        // 1) 可签节点（ACTIVE，SIGN/JOINT）
        LambdaQueryWrapper<ApprovalTask> signQw = new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getStatus, "ACTIVE")
                .in(ApprovalTask::getNodeType, Arrays.asList("SIGN", "JOINT"));
        if (!isAdmin) {
            signQw.in(ApprovalTask::getRoleRequired, roles);
        }
        List<ApprovalTask> signTasks = taskDao.selectList(signQw);

        // 2) 已升级通知（ESCALATE 节点，供质量总监等接收）
        LambdaQueryWrapper<ApprovalTask> escQw = new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getNodeType, "ESCALATE")
                .eq(ApprovalTask::getEscalated, "1");
        if (!isAdmin) {
            escQw.in(ApprovalTask::getRoleRequired, roles);
        }
        List<ApprovalTask> escalates = taskDao.selectList(escQw);

        List<ApprovalTask> merged = new ArrayList<>(signTasks);
        merged.addAll(escalates);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApprovalTask t : merged) {
            ApprovalInstance inst = instanceDao.selectById(t.getApprId());
            if (inst == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", t.getId());
            row.put("kind", "ESCALATE".equals(t.getNodeType()) ? "ESCALATE" : "SIGN");
            row.put("apprNo", inst.getApprNo());
            row.put("bizType", inst.getBizType());
            row.put("bizId", inst.getBizId());
            row.put("title", inst.getTitle());
            row.put("seq", t.getSeq());
            row.put("nodeName", t.getNodeName());
            row.put("roleRequired", t.getRoleRequired());
            row.put("applyBy", inst.getApplyBy());
            row.put("applyDate", inst.getApplyDate());
            row.put("remindCount", t.getRemindCount());
            row.put("escalated", t.getEscalated());
            row.put("opinion", t.getOpinion());
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> logs(String bizType, String bizId) {
        ApprovalInstance inst = findByBiz(bizType, bizId);
        if (inst == null) {
            return Map.of("instance", Collections.emptyMap(), "tasks", List.of());
        }
        List<ApprovalTask> tasks = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, inst.getId())
                .orderByAsc(ApprovalTask::getSeq)
                .orderByAsc(ApprovalTask::getCreateDate));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApprovalTask t : tasks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", t.getSeq());
            row.put("nodeType", t.getNodeType());
            row.put("nodeName", t.getNodeName());
            row.put("roleRequired", t.getRoleRequired());
            row.put("status", t.getStatus());
            row.put("signer", t.getSigner());
            row.put("signerName", t.getSignerName());
            row.put("opinion", t.getOpinion());
            row.put("opTime", t.getOpTime());
            row.put("remindCount", t.getRemindCount());
            row.put("escalated", t.getEscalated());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", inst);
        out.put("tasks", rows);
        return out;
    }

    // ---------- 超时扫描 ----------

    @Override
    @Transactional
    public int sweepTimeouts() {
        List<ApprovalTask> active = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getStatus, "ACTIVE")
                .in(ApprovalTask::getNodeType, Arrays.asList("SIGN", "JOINT")));
        int handled = 0;
        LocalDateTime now = LocalDateTime.now();
        for (ApprovalTask t : active) {
            LocalDateTime activated = t.getUpdateDate() != null ? t.getUpdateDate() : t.getCreateDate();
            if (activated == null) {
                continue;
            }
            long hours = Duration.between(activated, now).toHours();
            boolean changed = false;
            boolean remindDone = t.getRemindCount() != null && t.getRemindCount() > 0;
            if (hours >= remindHours && !remindDone) {
                t.setRemindCount(1);
                changed = true;
            }
            long days = Duration.between(activated, now).toDays();
            if (days >= escalateDays && !"1".equals(t.getEscalated())) {
                ApprovalInstance inst = instanceDao.selectById(t.getApprId());
                String target = inst != null && hasText(inst.getEscalateTo())
                        ? inst.getEscalateTo() : "ROLE_QUALITY_DIRECTOR";
                ApprovalTask esc = new ApprovalTask();
                esc.setApprId(t.getApprId());
                esc.setSeq(t.getSeq());
                esc.setNodeType("ESCALATE");
                esc.setRoleRequired(target);
                esc.setNodeName("超时升级待办");
                esc.setStatus("SKIPPED");
                esc.setOpinion("审批节点超 " + escalateDays + " 天未签署，已升级至 " + target);
                esc.setEscalated("1");
                esc.setRemindCount(0);
                taskDao.insert(esc);
                t.setEscalated("1");
                t.setEscalateTodoId(esc.getId());
                changed = true;
            }
            if (changed && taskDao.updateById(t) > 0) {
                handled++;
            }
        }
        return handled;
    }

    // ---------- 内部 ----------

    private ApprovalTask requireTask(String taskId) {
        ApprovalTask task = taskDao.selectById(taskId);
        if (task == null) {
            throw new ServiceException(404, "审批节点不存在");
        }
        if (!"SIGN".equals(task.getNodeType()) && !"JOINT".equals(task.getNodeType())) {
            throw new ServiceException(422, "该节点不可签署");
        }
        return task;
    }

    private ApprovalInstance requireInstance(String apprId) {
        ApprovalInstance inst = instanceDao.selectById(apprId);
        if (inst == null) {
            throw new ServiceException(404, "审批实例不存在");
        }
        return inst;
    }

    private void requireSignable(ApprovalTask task, ApprovalInstance inst) {
        if (!"ACTIVE".equals(task.getStatus())) {
            throw new ServiceException(422, "当前节点不可签署（可能已被他人处理）");
        }
        if (!"PENDING".equals(inst.getStatus())) {
            throw new ServiceException(422, "审批实例已办结");
        }
    }

    private void requireNodeRole(ApprovalTask task) {
        List<String> roles = currentRoles();
        if (roles.isEmpty()) {
            throw new ServiceException(401, "未登录");
        }
        boolean isAdmin = roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r));
        boolean match = roles.stream().anyMatch(r -> r.equalsIgnoreCase(task.getRoleRequired()));
        if (!isAdmin && !match) {
            throw new ServiceException(403, "当前角色无权签署该节点（需 " + task.getRoleRequired() + "）");
        }
    }

    private List<ApprovalTask> sameSeqSignNodes(ApprovalTask task) {
        return taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, task.getApprId())
                .eq(ApprovalTask::getSeq, task.getSeq())
                .in(ApprovalTask::getNodeType, Arrays.asList("SIGN", "JOINT")));
    }

    private ApprovalInstance advance(ApprovalInstance inst, int completedSeq) {
        int nextSeq = completedSeq + 1;
        List<ApprovalTask> next = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, inst.getId())
                .eq(ApprovalTask::getSeq, nextSeq));
        if (next.isEmpty()) {
            finish(inst, "APPROVED");
            fireCallback(inst, true);
            return inst;
        }
        for (ApprovalTask t : next) {
            if ("PENDING".equals(t.getStatus())) {
                t.setStatus("ACTIVE");
                // 刷新 UPDATE_DATE 作为该节点超时计时起点
                taskDao.updateById(t);
            }
        }
        return inst;
    }

    private void finish(ApprovalInstance inst, String status) {
        inst.setStatus(status);
        inst.setFinishDate(LocalDateTime.now());
        if (instanceDao.updateById(inst) == 0) {
            throw new ServiceException(422, "审批状态更新冲突，请刷新重试");
        }
    }

    private void fireCallback(ApprovalInstance inst, boolean approved) {
        ApprovalCallback cb = callbackMap.get(inst.getBizType());
        if (cb == null) {
            log.warn("no approval callback for bizType={} (id={})", inst.getBizType(), inst.getId());
            return;
        }
        if (approved) {
            cb.onApproved(inst);
        } else {
            cb.onRejected(inst);
        }
    }

    private String nextApprNo() {
        String prefix = "AP" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = instanceDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String displayName(String userId) {
        if (!hasText(userId)) {
            return userId;
        }
        try {
            SysUser u = sysUserDao.selectById(userId);
            if (u != null && hasText(u.getNickName())) {
                return u.getNickName();
            }
            if (u != null && hasText(u.getUsername())) {
                return u.getUsername();
            }
        } catch (Exception ignore) {
            // 用户表查不到时回退为登录名
        }
        return userId;
    }

    private List<String> currentRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        return roles;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
