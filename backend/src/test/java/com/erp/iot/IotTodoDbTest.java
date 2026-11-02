package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotMobileTodoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.7 移动待办库测（add-iot-mobile，FR-4.11-7-x / BR-4.11-42~47）：
 * 聚合查询 / 30min 超时升级 / 处理回写原单与通知 / 财务签署要求。
 */
@SpringBootTest
class IotTodoDbTest {

    @Autowired
    private IotMobileTodoService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-todo-";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-admin", null, List.of(
                        new SimpleGrantedAuthority("ROLE_PLAN_MGR"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='IOT_TODO' AND TITLE LIKE '%移动报工测试%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='IOT_TODO' AND CONTENT LIKE ?", "%" + P + "%");
        jdbc.update("DELETE FROM erp_iot_mobile_todo WHERE TITLE LIKE ?", "%" + P + "%");
        jdbc.update("DELETE FROM erp_iot_exception WHERE EXC_NO LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE ID LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_sys_approval WHERE TITLE LIKE ?", "%" + P + "%");
    }

    private Map<String, Object> todoBody(String type, String assignee, LocalDateTime dueAt) {
        Map<String, Object> b = new HashMap<>();
        b.put("todoType", type);
        b.put("refType", "IOT_EXCEPTION");
        b.put("refId", null);
        b.put("title", P + "待办-" + type);
        b.put("assignee", assignee);
        b.put("dueAt", dueAt == null ? LocalDateTime.now().plusMinutes(30).toString()
                : dueAt.toString());
        return b;
    }

    @Test
    void aggregateSortsByDueAndMergesApprovalFlow() {
        // 两条本域待办（不同超时）+ 一条审批流在途节点
        service.create(todoBody("EXCEPTION", "user-admin", LocalDateTime.now().plusMinutes(10)));
        service.create(todoBody("REPORT", "user-admin", LocalDateTime.now().plusMinutes(5)));
        jdbc.update("INSERT INTO erp_sys_approval (ID, APPR_NO, BIZ_TYPE, BIZ_ID, TITLE, " +
                        "STATUS, APPLY_BY, APPLY_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?, ?, 'MoApprove', 'mo-test-1', ?, 'PENDING', 'junit', NOW(), " +
                        "'junit', NOW(), '0', 0)",
                P + "appr-1", "APR-" + P, P + "审批流待办");
        jdbc.update("INSERT INTO erp_sys_approval_task (ID, APPR_ID, SEQ, NODE_TYPE, " +
                        "ROLE_REQUIRED, NODE_NAME, SIGNER, STATUS, DEL_FLAG, VER_NO) " +
                        "VALUES (?, ?, 1, 'SIGN', 'ROLE_PLAN_MGR', '计划主管签', 'user-admin', " +
                        "'PENDING', '0', 0)",
                P + "task-1", P + "appr-1");
        List<Map<String, Object>> list = service.aggregate("user-admin", "PENDING");
        assertTrue(list.size() >= 3, "聚合本域待办 + 审批流节点，实际 " + list.size());
        assertTrue(list.stream().anyMatch(t -> "APPROVAL_FLOW".equals(t.get("source"))),
                "审批流待办并入统一视图（BR-4.11-42）");
        // 按超时倒计时排序（最早在前）
        String firstTitle = String.valueOf(list.get(0).get("title"));
        assertTrue(firstTitle.contains("REPORT") || firstTitle.contains("审批"),
                "紧急待办在前，首条=" + firstTitle);
    }

    @Test
    void overdueTodoEscalatesToManager() {
        Map<String, Object> created = service.create(
                todoBody("EXCEPTION", "user-admin", LocalDateTime.now().minusMinutes(31)));
        Map<String, Object> out = service.scanEscalate();
        assertTrue(((Number) out.get("escalated")).intValue() >= 1, "30min 超时升级");
        // 升级留痕 + 重新计时
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ESCALATED_TO, ESCALATED_AT, DUE_AT, STATUS FROM erp_iot_mobile_todo WHERE ID=?",
                created.get("id"));
        assertNotNull(row.get("ESCALATED_TO"), "升级对象留痕");
        assertNotNull(row.get("ESCALATED_AT"), "升级时间留痕");
        assertTrue(((LocalDateTime) row.get("DUE_AT")).isAfter(LocalDateTime.now()),
                "升级后重新计时 30min");
        assertEquals("PENDING", row.get("STATUS"), "升级后仍可处理");
    }

    @Test
    void handleWritesBackAndNotifies() {
        // REF_TYPE=IOT_EXCEPTION → 处理回写异常单 + 通知发起人
        jdbc.update("INSERT INTO erp_iot_exception (ID, EXC_NO, EXC_TYPE, SEVERITY, " +
                        "STATUS, SOURCE, CONFIRM_FLAG, MERGE_CNT, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?, 'EQUIPMENT', 'SEVERE', 'OPEN', 'MANUAL', '1', 1, NOW(), '0', 0)",
                P + "exc-id", P + "EXC-1");
        Map<String, Object> body = todoBody("EXCEPTION", "user-admin",
                LocalDateTime.now().plusMinutes(10));
        body.put("refId", P + "exc-id");
        Map<String, Object> created = service.create(body);
        Map<String, Object> handled = service.handle((String) created.get("id"),
                "确认处置", "通过");
        assertEquals("DONE", handled.get("status"));
        assertEquals(1, handled.get("writtenBack"), "回写原异常单");
        assertEquals("NOTIFIED", handled.get("notifiedOriginator"), "通知发起人");
        // 异常单已 CLOSED
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_iot_exception WHERE ID=?",
                String.class, P + "exc-id");
        assertEquals("CLOSED", st, "回写原单状态");
        // 通知落库
        Integer notice = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='IOT_TODO' AND BIZ_ID=?",
                Integer.class, created.get("id"));
        assertEquals(1, notice, "完成通知落库");
        // 重复处理 409
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.handle((String) created.get("id"), "x", "通过"));
        assertEquals(409, ex.getCode());
    }

    @Test
    void financeSignRequiresOpinion() {
        Map<String, Object> body = todoBody("APPROVAL", "user-admin",
                LocalDateTime.now().plusMinutes(10));
        body.put("needFinSign", true);
        Map<String, Object> created = service.create(body);
        // 财务签署待办无意见 → 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.handle((String) created.get("id"), null, "APPROVE"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("财务"));
        // 带意见通过
        Map<String, Object> ok = service.handle((String) created.get("id"),
                "财务主管批准", "APPROVE");
        assertEquals("DONE", ok.get("status"));
    }
}
