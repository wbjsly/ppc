package com.erp.ops;

import com.erp.common.ServiceException;
import com.erp.service.ops.ChangeTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 18.1 变更单库测（add-ops-sla，spec ops-sla）：
 * 预案三件套门禁（BR-5.8-01）/ 回滚原因强制（BR-5.8-04）/ 结账保障（BR-5.8-14）。
 */
@SpringBootTest
class ChangeTicketDbTest {

    @Autowired
    private ChangeTicketService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String TITLE = "CT-TEST-";

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    @Test
    void submitAndApproveWithFullPlan() {
        String id = submit("完整预案", planJson(true));
        Map<String, Object> r = service.approve(id);
        assertEquals("APPROVED", r.get("status"));
        assertNotNull(r.get("inWindow"), "窗口判定已记录");
    }

    @Test
    void approveBlockedWhenPlanMissing() {
        String id = submit("无预案", null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.approve(id));
        assertEquals(422, ex.getCode());
    }

    @Test
    void approveBlockedWhenDrillInvalid() {
        String id = submit("演练无效", planJson(false));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.approve(id));
        assertEquals(422, ex.getCode());
    }

    @Test
    void closeBlockedWhenRollbackReasonMissing() {
        String id = submit("回滚原因门禁", planJson(true));
        service.approve(id);
        service.rollback(id, "执行失败");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.close(id, null));
        assertEquals(422, ex.getCode());
        // 填原因后可关闭
        Map<String, Object> r = service.close(id, "配置错误已修复");
        assertEquals("CLOSED", r.get("status"));
    }

    @Test
    void closeNormalWithoutRollback() {
        String id = submit("正常关闭", planJson(true));
        service.approve(id);
        Map<String, Object> r = service.close(id, null);
        assertEquals("CLOSED", r.get("status"), "未回滚可直接关闭");
    }

    @Test
    void accountingGuardBlocksNormalChange() {
        // 开启结账保障 + 造 1~5 日内日期语义（服务内按当前日判定，当前若不在 1~5 日则跳过断言）
        jdbc.update("INSERT INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, "
                + "REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "(UUID(), 'ACCOUNTING_GUARD_ENABLED', '1', 'STRING', 'OPS', 't', 't', NOW(), '0', 0) "
                + "ON DUPLICATE KEY UPDATE PARAM_VALUE='1', REMARK='t'");
        int day = java.time.LocalDateTime.now().getDayOfMonth();
        if (day >= 1 && day <= 5) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> submit("结账期普通变更", planJson(true)));
            assertEquals(422, ex.getCode());
            // 紧急修复放行
            Map<String, Object> body = body("结账期紧急", planJson(true));
            body.put("changeType", "EMERGENCY_FIX");
            assertNotNull(service.submit(body).get("id"));
        } else {
            // 非结账窗口普通变更放行
            assertNotNull(submit("非结账期", planJson(true)));
        }
    }

    @Test
    void startBlockedWhenNotInWindow() {
        String id = submit("启动门禁", planJson(true));
        service.approve(id);
        // 开发库无窗口定义时不在窗口内 → 422（BR-5.8-06）
        Integer windows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_change_window WHERE STATUS='1' AND DEL_FLAG='0'",
                Integer.class);
        if (windows == null || windows == 0) {
            ServiceException ex = assertThrows(ServiceException.class, () -> service.start(id));
            assertEquals(422, ex.getCode());
        }
    }

    @Test
    void listWorks() {
        submit("台账查询", planJson(true));
        assertTrue(service.list(null, 1, 10).size() >= 1);
    }

    // ---------------- 工具 ----------------

    private String submit(String suffix, String plan) {
        Map<String, Object> r = service.submit(body(TITLE + suffix, plan));
        return String.valueOf(r.get("id"));
    }

    private Map<String, Object> body(String title, String plan) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("title", title);
        m.put("changeType", "NORMAL");
        m.put("estimateMin", 60);
        m.put("rollbackMin", 15);
        m.put("rollbackPlanJson", plan == null ? "" : plan);
        return m;
    }

    private String planJson(boolean drillValid) {
        return "{\"script\":\"downgrade.sh\",\"drillValid\":" + drillValid + ",\"estimateMin\":10}";
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_ops_change_ticket WHERE TITLE LIKE ?", TITLE + "%");
        jdbc.update("DELETE FROM erp_sys_param WHERE PARAM_KEY='ACCOUNTING_GUARD_ENABLED'");
    }
}
