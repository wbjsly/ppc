package com.erp.sd;

import com.erp.common.ServiceException;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.sd.QuoteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 报价审批台 DbTest（add-quote-approval-console，spec sales-quote 报价审批台 /
 * approval-workflow 按业务类型列实例）。
 * 造数前缀 qac-；SQL 直插 DRAFT 报价（创建链路非本 change 范围），走 submit→签署真实链。
 */
@SpringBootTest
class QuoteApprovalConsoleDbTest {

    @Autowired
    private QuoteService quoteService;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        authAs("junit-mgr", "ROLE_SALES_MGR");
    }

    @AfterEach
    void tearDown() {
        purge();
        getContext().setAuthentication(null);
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_ID LIKE 'qac-%'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'qac-%'");
        jdbc.update("DELETE FROM erp_sd_quote WHERE ID LIKE 'qac-%'");
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                user, null, org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(roles)));
    }

    /** 直插 DRAFT 报价（金额/毛利可控），返回报价 ID */
    private String seedQuote(String suffix, String marginRate) {
        String id = "qac-q-" + suffix;
        jdbc.update("INSERT INTO erp_sd_quote (ID, DRAFT_NO, OPP_ID, CUSTOMER_ID, CUSTOMER_NAME, ORDER_TYPE, "
                + "TOTAL_AMOUNT, TOTAL_COST, MARGIN_RATE, VERSION_NO, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'qac-opp', 'qac-cust', '审批台客户', 'STANDARD', 100000, 80000, ?, 1, 'DRAFT', 'junit', '0', 0)",
                id, "QDQAC-" + suffix.toUpperCase(), new BigDecimal(marginRate));
        return id;
    }

    @Test
    void historyFiltersByBizTypeAndStatus() {
        String qid = seedQuote("h1", "20");
        quoteService.submit(qid);
        // 未办结：APPROVED 过滤不含本单，PENDING 命中
        List<Map<String, Object>> approved = engine.listByBizType("Quote", "APPROVED");
        assertTrue(approved.stream().noneMatch(r -> qid.equals(r.get("bizId"))), "未办结不入 APPROVED");
        List<Map<String, Object>> pending = engine.listByBizType("Quote", "PENDING");
        assertTrue(pending.stream().anyMatch(r -> qid.equals(r.get("bizId"))), "PENDING 含本单：" + pending.size());
        // 其他类型不受影响
        assertTrue(engine.listByBizType("NonExistBiz", null).isEmpty());
        // bizType 必填
        assertThrows(ServiceException.class, () -> engine.listByBizType(" ", null));
    }

    @Test
    void todoVisibilityByRole() {
        // 普通报价 → SALES_MGR 单签
        String normal = seedQuote("v1", "20");
        quoteService.submit(normal);
        authAs("junit-mgr", "ROLE_SALES_MGR");
        List<Map<String, Object>> mgrTodo = engine.todo();
        assertTrue(mgrTodo.stream().anyMatch(r -> normal.equals(r.get("bizId"))), "SALES_MGR 可见普通报价待办");
        authAs("junit-fin", "ROLE_FINANCE_MGR");
        List<Map<String, Object>> finTodo = engine.todo();
        assertTrue(finTodo.stream().noneMatch(r -> normal.equals(r.get("bizId"))), "财务不见普通报价节点");
        // 负毛利 → 双人会签：财务可见自己节点（提交须回销售经理上下文）
        authAs("junit-mgr", "ROLE_SALES_MGR");
        String negative = seedQuote("v2", "-5");
        quoteService.submit(negative);
        authAs("junit-fin", "ROLE_FINANCE_MGR");
        List<Map<String, Object>> finTodo2 = engine.todo();
        assertTrue(finTodo2.stream().anyMatch(r -> negative.equals(r.get("bizId"))), "财务可见负毛利会签节点");
        authAs("junit-dir", "ROLE_SALES_DIRECTOR");
        // 普通 10 万 < 50 万大额线 → 无总监节点
        assertTrue(engine.todo().stream().noneMatch(r -> normal.equals(r.get("bizId"))));
    }

    @Test
    void financeAndDirectorCanReadQuoteDetail() {
        String qid = seedQuote("r1", "20");
        authAs("junit-fin", "ROLE_FINANCE_MGR");
        assertNotNull(quoteService.get(qid), "财务可读报价头");
        assertEquals(0, quoteService.lines(qid).size(), "财务可读报价行（本造数无行）");
        authAs("junit-dir", "ROLE_SALES_DIRECTOR");
        assertNotNull(quoteService.get(qid));
        assertNotNull(quoteService.versions(qid));
    }

    @Test
    void rejectOpinionMinLength() {
        String qid = seedQuote("j1", "20");
        quoteService.submit(qid);
        String taskId = jdbc.queryForObject("SELECT t.ID FROM erp_sys_approval_task t "
                        + "JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, qid);
        authAs("junit-mgr", "ROLE_SALES_MGR");
        // 意见 1 字 → 422
        ServiceException ex = assertThrows(ServiceException.class, () -> engine.reject(taskId, "不"));
        assertTrue(ex.getMessage().contains("2"), ex.getMessage());
        // 实例仍 PENDING
        ApprovalInstance inst = engine.findByBiz("Quote", qid);
        assertEquals("PENDING", inst.getStatus());
    }

    @Test
    void passApprovesAndHistoryShows() {
        String qid = seedQuote("p1", "20");
        quoteService.submit(qid);
        String taskId = jdbc.queryForObject("SELECT t.ID FROM erp_sys_approval_task t "
                        + "JOIN erp_sys_approval i ON t.APPR_ID=i.ID WHERE i.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, qid);
        authAs("junit-mgr", "ROLE_SALES_MGR");
        engine.pass(taskId, "同意发布");
        ApprovalInstance inst = engine.findByBiz("Quote", qid);
        assertEquals("APPROVED", inst.getStatus());
        assertNotNull(inst.getFinishDate(), "办结时间落库");
        // history APPROVED 过滤可见，当前节点摘要为空（无 ACTIVE）
        List<Map<String, Object>> history = engine.listByBizType("Quote", "APPROVED");
        Map<String, Object> row = history.stream().filter(r -> qid.equals(r.get("bizId"))).findFirst().orElse(null);
        assertNotNull(row, "已审历史可见");
        assertEquals(null, row.get("nodeName"));
        // 报价回调已发布（quoteNo 生成）——QuoteApprovalCallback 与审批同事务
        String status = jdbc.queryForObject("SELECT STATUS FROM erp_sd_quote WHERE ID=?", String.class, qid);
        assertEquals("PUBLISHED", status, "通过后按回调发布");
    }
}
