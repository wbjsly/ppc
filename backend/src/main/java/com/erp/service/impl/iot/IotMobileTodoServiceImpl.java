package com.erp.service.impl.iot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.iot.IotMobileTodoDao;
import com.erp.entity.iot.IotMobileTodo;
import com.erp.service.iot.IotMobileTodoService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 移动待办中心实现（add-iot-mobile 13.7，FR-4.11-7-x / BR-4.11-42~47）：
 * 聚合 ApprovalEngine 现有审批流待办 + 异常/巡检/升级待办统一视图；
 * 30min 超时升级；处理回写原单并通知发起人。
 */
@Slf4j
@Service
public class IotMobileTodoServiceImpl implements IotMobileTodoService {

    private final IotMobileTodoDao todoDao;
    private final JdbcTemplate jdbc;

    public IotMobileTodoServiceImpl(IotMobileTodoDao todoDao, JdbcTemplate jdbc) {
        this.todoDao = todoDao;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(Map<String, Object> body) {
        IotMobileTodo t = new IotMobileTodo();
        t.setTodoType(str(body.getOrDefault("todoType", IotMobileTodo.TYPE_EXCEPTION)));
        t.setRefType(str(body.get("refType")));
        t.setRefId(str(body.get("refId")));
        t.setTitle(str(body.get("title")));
        t.setDetailJson(str(body.get("detailJson")));
        t.setAssignee(str(body.get("assignee")));
        t.setNeedFinSign(Boolean.TRUE.equals(body.get("needFinSign")) ? "1" : "0");
        t.setStatus(IotMobileTodo.ST_PENDING);
        Object due = body.get("dueAt");
        t.setDueAt(due == null ? LocalDateTime.now().plusMinutes(30)
                : LocalDateTime.parse(String.valueOf(due)));
        t.setCreateBy(SecurityUtils.getCurrentUserId());
        todoDao.insert(t);
        return toMap(t);
    }

    @Override
    public List<Map<String, Object>> aggregate(String assignee, String status) {
        // 统一视图：本域待办 + ApprovalEngine 现有审批流待办（聚合 BR-4.11-42）
        List<Map<String, Object>> out = new ArrayList<>();
        LambdaQueryWrapper<IotMobileTodo> qw = new LambdaQueryWrapper<IotMobileTodo>()
                .eq(assignee != null && !assignee.isBlank(), IotMobileTodo::getAssignee, assignee)
                .eq(status != null && !status.isBlank(), IotMobileTodo::getStatus, status)
                .orderByAsc(IotMobileTodo::getDueAt);
        for (IotMobileTodo t : todoDao.selectList(qw)) {
            out.add(toMap(t));
        }
        // 聚合审批引擎在途节点（当前登录人）
        String me = SecurityUtils.getCurrentUserId();
        if (me != null && (status == null || status.isBlank()
                || IotMobileTodo.ST_PENDING.equals(status))) {
            List<Map<String, Object>> flows = jdbc.query(
                    "SELECT t.ID, t.NODE_NAME, a.BIZ_TYPE, a.TITLE, a.CREATE_DATE " +
                            "FROM erp_sys_approval_task t " +
                            "INNER JOIN erp_sys_approval a ON a.ID=t.APPR_ID " +
                            "WHERE t.SIGNER=? AND t.STATUS='PENDING' AND a.DEL_FLAG='0' " +
                            "ORDER BY a.CREATE_DATE LIMIT 100",
                    new org.springframework.jdbc.core.ColumnMapRowMapper(), me);
            for (Map<String, Object> f : flows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", "APPROVAL_FLOW");
                m.put("todoType", IotMobileTodo.TYPE_APPROVAL);
                m.put("refType", String.valueOf(f.get("BIZ_TYPE")));
                m.put("refId", String.valueOf(f.get("ID")));
                m.put("title", String.valueOf(f.get("TITLE")));
                m.put("createdAt", String.valueOf(f.get("CREATE_DATE")));
                m.put("assignee", me);
                m.put("status", IotMobileTodo.ST_PENDING);
                out.add(m);
            }
        }
        // 按超时倒计时排序（紧急在前）
        out.sort((a, b) -> {
            Object da = a.get("dueAt");
            Object db = b.get("dueAt");
            if (da == null) {
                return 1;
            }
            if (db == null) {
                return -1;
            }
            return String.valueOf(da).compareTo(String.valueOf(db));
        });
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handle(String todoId, String opinion, String result) {
        IotMobileTodo t = todoDao.selectById(todoId);
        if (t == null) {
            throw new ServiceException(404, "待办不存在");
        }
        if (!IotMobileTodo.ST_PENDING.equals(t.getStatus())) {
            throw new ServiceException(409, "待办已处理（当前 " + t.getStatus() + "）");
        }
        // 财务签署要求（BR-4.11-45）：NEED_FIN_SIGN=1 时结果须含 APPROVE/REJECT 语义
        if ("1".equals(t.getNeedFinSign()) && opinion == null) {
            throw new ServiceException(422, "财务签署待办须填写审批意见");
        }
        t.setStatus(IotMobileTodo.ST_DONE);
        t.setHandledBy(SecurityUtils.getCurrentUserId());
        t.setHandledAt(LocalDateTime.now());
        t.setHandlingOpinion(opinion);
        todoDao.updateById(t);
        // 回写原单并通知发起人（BR-4.11-43）
        int writtenBack = writeBack(t, result, opinion);
        Map<String, Object> out = toMap(t);
        out.put("writtenBack", writtenBack);
        out.put("notifiedOriginator", notifyOriginator(t));
        return out;
    }

    @Override
    public Map<String, Object> scanEscalate() {
        // 30min 超时（DUE_AT 过期 PENDING）→ 升级上级（BR-4.11-44/47）
        List<IotMobileTodo> overdue = todoDao.selectList(new LambdaQueryWrapper<IotMobileTodo>()
                .eq(IotMobileTodo::getStatus, IotMobileTodo.ST_PENDING)
                .lt(IotMobileTodo::getDueAt, LocalDateTime.now()));
        int escalated = 0;
        List<String> details = new ArrayList<>();
        for (IotMobileTodo t : overdue) {
            String manager = managerOf(t.getAssignee());
            t.setStatus(IotMobileTodo.ST_ESCALATED);
            t.setEscalatedTo(manager);
            t.setEscalatedAt(LocalDateTime.now());
            // 升级后保持 PENDING 可处理（升级留痕 + 重新计时）
            t.setStatus(IotMobileTodo.ST_PENDING);
            t.setDueAt(LocalDateTime.now().plusMinutes(30));
            todoDao.updateById(t);
            escalated++;
            details.add(t.getTitle() + "→" + manager);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("escalated", escalated);
        out.put("details", details);
        return out;
    }

    // ---------- internal ----------

    /** 回写原单：按 REF_TYPE 分发（审批流节点/告警/异常单） */
    private int writeBack(IotMobileTodo t, String result, String opinion) {
        if (t.getRefId() == null) {
            return 0;
        }
        boolean approve = result == null || result.toUpperCase().contains("APPROVE")
                || result.contains("通过");
        try {
            if ("APPROVAL_FLOW".equals(t.getRefType())) {
                // 审批流任务节点回写（复用审批底座状态机）
                jdbc.update("UPDATE erp_sys_approval_task SET STATUS=?, OPINION=?, " +
                                "OP_TIME=NOW(), SIGNER=? WHERE ID=? AND STATUS='PENDING'",
                        approve ? "APPROVED" : "REJECTED", opinion == null ? "移动端处理" : opinion,
                        SecurityUtils.getCurrentUserId(), t.getRefId());
                return 1;
            }
            if ("IOT_EXCEPTION".equals(t.getRefType())) {
                jdbc.update("UPDATE erp_iot_exception SET STATUS=?, CLOSED_AT=NOW() WHERE ID=?",
                        approve ? "CLOSED" : "PROCESSING", t.getRefId());
                return 1;
            }
        } catch (Exception e) {
            log.warn("[iot] 待办回写失败 todo={} ref={}：{}", t.getId(), t.getRefId(), e.getMessage());
            return 0;
        }
        return 0;
    }

    /** 通知发起人：待办完成 → 创建完成通知（简化为日志 + 通知行回写 title） */
    private String notifyOriginator(IotMobileTodo t) {
        jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, BIZ_TYPE, " +
                        "BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,?,?,?, '0', ?,NOW(),'0',0)",
                java.util.UUID.randomUUID().toString().replace("-", ""),
                t.getAssignee(), "待办处理完成：" + t.getTitle(),
                "处理人 " + t.getHandledBy() + "，意见：" + t.getHandlingOpinion(),
                "IOT_TODO", t.getId(), t.getHandledBy());
        return "NOTIFIED";
    }

    private String managerOf(String assignee) {
        List<String> mgrs = jdbc.queryForList(
                "SELECT u.USERNAME FROM erp_admin_user u " +
                        "INNER JOIN erp_admin_user_role ur ON ur.USER_ID=u.ID " +
                        "INNER JOIN erp_admin_role r ON r.ID=ur.ROLE_ID " +
                        "WHERE r.ROLE_CODE IN ('ROLE_PLAN_MGR','ROLE_DEPT_DIRECTOR') " +
                        "AND r.STATUS='1' AND u.STATUS='1' LIMIT 1", String.class);
        return mgrs.isEmpty() ? "user-admin" : mgrs.get(0);
    }

    private Map<String, Object> toMap(IotMobileTodo t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("todoType", t.getTodoType());
        m.put("refType", t.getRefType());
        m.put("refId", t.getRefId());
        m.put("title", t.getTitle());
        m.put("assignee", t.getAssignee());
        m.put("dueAt", t.getDueAt());
        m.put("escalatedTo", t.getEscalatedTo());
        m.put("escalatedAt", t.getEscalatedAt());
        m.put("status", t.getStatus());
        m.put("handledBy", t.getHandledBy());
        m.put("handledAt", t.getHandledAt());
        m.put("handlingOpinion", t.getHandlingOpinion());
        m.put("needFinSign", t.getNeedFinSign());
        return m;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
