package com.erp.proc;

import com.erp.common.ServiceException;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.util.SecurityUtils;
import com.erp.service.proc.PoCoopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PO 协同操作台增量（add-po-collaboration-console，spec po-collaboration 增补三 reqs）：
 * - 连续 3 张超时锁定 → OPEN 工单；resolve → DONE + L5 留痕；无工单不得解锁
 * - 发起协商 NEGOTIATE 留痕（时间线可见）
 * - confirm 结构化数量建议（QTY_SUGGESTED/QTY_REASON，不写回 PO）
 * - 工单分页与推送台账查询
 * 造数前缀 PCC。
 */
@SpringBootTest
class PoCoopConsoleDbTest {

    private static final String SUP = "PCC-SUP";

    @Autowired
    private PoCoopService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        asRole("ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    // ==================== 2.1 锁定生成工单 + 2.2 工单解锁闭环 ====================

    @Test
    void lockOpensTicketAndResolveLeavesTrail() {
        // 三张 APPROVED PO 已推送且超时（ACTION_AT 回拨 80h > escalateHours 72）
        seedPushedOverdue("PCC-1");
        seedPushedOverdue("PCC-2");
        seedPushedOverdue("PCC-3");
        // 列表遍历触发惰性超时链 → ESCATED + 连续 3 张锁定 + OPEN 工单
        service.page(1, 50, null, null, null);

        // coopLock 只设在锁定评估时的当前 PO（展示一致性冗余，判定以流水/工单为准）
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_proc_po WHERE SUPPLIER_ID=? AND COOP_LOCK='1'",
                Long.class, SUP) >= 1, "至少一张 PO coopLock=1");
        Map<String, Object> open = jdbc.queryForMap(
                "SELECT * FROM erp_po_coop_ticket WHERE SUPPLIER_ID=? AND STATUS='OPEN'", SUP);
        // 清单顺序随遍历顺序非确定（同秒推送排序不稳）→ 按集合断言
        java.util.Set<String> nos = new java.util.HashSet<>(
                java.util.Arrays.asList(String.valueOf(open.get("LOCK_PO_NOS")).split("、")));
        assertEquals(java.util.Set.of("PCC-1", "PCC-2", "PCC-3"), nos, "工单含 3 张 PO 单号清单");
        assertNotNull(open.get("TICKET_NO"));
        assertNotNull(open.get("LOCK_AT"));
        assertTrue(service.supplierLocked(SUP), "锁定态生效（确认入口关闭）");

        // 无工单路径被收紧：先 resolve 唯一 OPEN 工单，再走 unlocks → 422
        Map<String, Object> done = service.resolveTicket(String.valueOf(open.get("ID")), "继续合作，观察下批交付");
        assertEquals("DONE", done.get("status"));
        assertEquals(SecurityUtils.getCurrentUserId(), done.get("unlockBy"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT STATUS, UNLOCK_BY, UNLOCK_REASON FROM erp_po_coop_ticket WHERE SUPPLIER_ID=?", SUP);
        assertEquals("DONE", row.get("STATUS"));
        assertEquals(SecurityUtils.getCurrentUserId(), row.get("UNLOCK_BY"));
        assertNotNull(row.get("UNLOCK_REASON"));
        String locks = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(DISTINCT COOP_LOCK) FROM erp_proc_po WHERE SUPPLIER_ID=?",
                String.class, SUP);
        assertEquals("0", locks, "工单解锁后 coopLock 清零");

        // spec：无工单不得解锁
        ServiceException e = assertThrows(ServiceException.class, () -> service.unlock(SUP, "直接解锁"));
        assertEquals(422, e.getCode());

        // 时间线含 LOCK 与 UNLOCK（经工单）
        Long lockRows = jdbc.queryForObject("SELECT COUNT(*) FROM erp_proc_po_coop " +
                "WHERE SUPPLIER_ID=? AND ACTION_TYPE='LOCK'", Long.class, SUP);
        Long unlockRows = jdbc.queryForObject("SELECT COUNT(*) FROM erp_proc_po_coop " +
                "WHERE SUPPLIER_ID=? AND ACTION_TYPE='UNLOCK' AND DETAIL LIKE '%工单%'", Long.class, SUP);
        assertTrue(lockRows >= 1 && unlockRows >= 1, "LOCK/UNLOCK 时间线留痕");
    }

    @Test
    void resolveRequiresOpenTicketAndReason() {
        ServiceException noTicket = assertThrows(ServiceException.class,
                () -> service.resolveTicket("no-such", "reason"));
        assertEquals(404, noTicket.getCode());

        seedPushedOverdue("PCC-B1");
        seedPushedOverdue("PCC-B2");
        seedPushedOverdue("PCC-B3");
        service.page(1, 50, null, null, null);
        String tid = jdbc.queryForObject(
                "SELECT ID FROM erp_po_coop_ticket WHERE SUPPLIER_ID=? AND STATUS='OPEN'", String.class, SUP);
        ServiceException noReason = assertThrows(ServiceException.class,
                () -> service.resolveTicket(tid, " "));
        assertEquals(422, noReason.getCode(), "解锁原因必填（L5 留痕）");
    }

    // ==================== 2.3 协商留痕 ====================

    @Test
    void negotiateWritesTimelineRow() {
        PurchaseOrder po = seedPushed("PCC-N1");
        service.negotiate(po.getId(), Map.of("note", "请评估提前至 11-05 的可行性"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT DETAIL, SOURCE FROM erp_proc_po_coop WHERE PO_NO='PCC-N1' AND ACTION_TYPE='NEGOTIATE'");
        assertTrue(String.valueOf(row.get("DETAIL")).contains("提前至 11-05"), "协商说明落明细");
        // PO 状态未被协商改变（保持未确认）
        assertEquals("PUSHED", jdbc.queryForObject(
                "SELECT CONFIRM_STATUS FROM erp_proc_po WHERE PO_NO='PCC-N1'", String.class));
        // 时间线接口可见
        assertTrue(service.timeline(po.getId()).stream()
                .anyMatch(r -> "NEGOTIATE".equals(String.valueOf(r.get("actionType")))), "时间线含协商动作");
        // 说明必填
        ServiceException e = assertThrows(ServiceException.class, () -> service.negotiate(po.getId(), Map.of()));
        assertEquals(422, e.getCode());
    }

    // ==================== 2.4 结构化数量建议 ====================

    @Test
    void confirmCarriesStructuredQtyWithoutWritingPo() {
        PurchaseOrder po = seedPushed("PCC-Q1");
        BigDecimal before = jdbc.queryForObject(
                "SELECT TOTAL_AMT FROM erp_proc_po WHERE PO_NO='PCC-Q1'", BigDecimal.class);
        service.confirm(po.getId(), Map.of(
                "promiseDate", "2026-11-10",
                "qtySuggested", "100", "qtyReason", "产能不足", "source", "PORTAL"), "PORTAL");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT QTY_SUGGESTED, QTY_REASON, PROMISE_DATE FROM erp_proc_po_coop " +
                        "WHERE PO_NO='PCC-Q1' AND ACTION_TYPE='CONFIRM'");
        assertEquals(new BigDecimal("100.000"), row.get("QTY_SUGGESTED"), "结构化建议数量落库");
        assertEquals("产能不足", row.get("QTY_REASON"));
        assertNotNull(row.get("PROMISE_DATE"));
        // 数量建议不写回 PO
        assertEquals(before, jdbc.queryForObject(
                "SELECT TOTAL_AMT FROM erp_proc_po WHERE PO_NO='PCC-Q1'", BigDecimal.class));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT CONFIRM_STATUS FROM erp_proc_po WHERE PO_NO='PCC-Q1'", String.class));
    }

    // ==================== 2.5 工单分页与推送台账 ====================

    @Test
    void ticketsPagedAndPushLedgerFromOutbox() {
        seedPushedOverdue("PCC-L1");
        seedPushedOverdue("PCC-L2");
        seedPushedOverdue("PCC-L3");
        service.page(1, 50, null, null, null);
        // tickets 是全局查询（库里可能有其它供应商工单残留）→ 按本供应商口径断言
        assertEquals(1, service.tickets(1, 50, "OPEN").getRecords().stream()
                .filter(r -> SUP.equals(r.get("supplierId"))).count(), "本供应商 OPEN 工单可查");

        // 推送台账来自 outbox（push() 时事务性发件箱已写 PROC.PO_PUSHED）
        assertTrue(service.pushLedger(1, 20, "PCC-L1").getTotal() >= 1, "推送台账含该 PO 事件");
        assertEquals(0, service.pushLedger(1, 20, "NOPE-NO-PO").getTotal());
    }

    // ==================== 帮助方法 ====================

    /** APPROVED PO + push（写 PUSHED 流水与 outbox） */
    private PurchaseOrder seedPushed(String poNo) {
        jdbc.update("INSERT INTO erp_proc_po (ID,PO_NO,PO_TYPE,SOURCE,SUPPLIER_ID,SUPPLIER_NAME," +
                        "STATUS,APPROVAL_BATCH,CURRENCY,TOTAL_AMT,CURR_VERSION,CONFIRM_STATUS,COOP_LOCK,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) " +
                        "VALUES (?,?, 'NORMAL','MANUAL',?, 'PCC 供应商','APPROVED',0,'CNY',1000,1,'PUSHED','0','smoke',NOW(),'0',0)",
                "pcc-" + poNo.toLowerCase(), poNo, SUP);
        PurchaseOrder po = jdbc.queryForObject(
                "SELECT * FROM erp_proc_po WHERE PO_NO=?", (rs, i) -> {
                    PurchaseOrder p = new PurchaseOrder();
                    p.setId(rs.getString("ID"));
                    p.setPoNo(rs.getString("PO_NO"));
                    p.setSupplierId(rs.getString("SUPPLIER_ID"));
                    p.setCurrVersion(rs.getInt("CURR_VERSION"));
                    // poType/totalAmt 缺失会触发 PROC.PO_PUSHED 载荷校验 L3 拒发（FR-4.9-2-2）
                    p.setPoType(rs.getString("PO_TYPE"));
                    p.setTotalAmt(rs.getBigDecimal("TOTAL_AMT"));
                    return p;
                }, poNo);
        service.push(po);
        return po;
    }

    /** 推送后把 PUSHED 流水回拨 80h（超 escalateHours） */
    private PurchaseOrder seedPushedOverdue(String poNo) {
        PurchaseOrder po = seedPushed(poNo);
        jdbc.update("UPDATE erp_proc_po_coop SET ACTION_AT = DATE_SUB(NOW(), INTERVAL 80 HOUR) " +
                "WHERE PO_NO=? AND ACTION_TYPE='PUSHED'", poNo);
        return po;
    }

    private void asRole(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "pcc-tester", "x", roles));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_po_coop_ticket WHERE SUPPLIER_ID=?", SUP);
        jdbc.update("DELETE FROM erp_proc_po_coop WHERE SUPPLIER_ID=?", SUP);
        jdbc.update("DELETE FROM erp_proc_po WHERE SUPPLIER_ID=?", SUP);
    }
}
