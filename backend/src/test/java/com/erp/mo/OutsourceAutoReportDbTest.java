package com.erp.mo;

import com.erp.service.mrp.DispatchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 4.1 收货自动报工闭环库测（spec outsourcing-execution「收货自动报工闭环」+
 * outsourcing-dispatch MODIFIED「收货回联触发自动报工」）：
 * 一次收足自动报工+完成工序 / 部分收货按次累计 / 同 GR 幂等防重 / RECV_QTY 累计。
 */
@SpringBootTest
class OutsourceAutoReportDbTest {

    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        cleanup();
        // 工单（计划量 100）
        jdbc.update("INSERT INTO erp_mrp_mo (ID, MO_NO, PRODUCT_CODE, QTY, STATUS, DEL_FLAG, VER_NO) "
                + "VALUES ('ao-mo1', 'MOAO01', 'PRD-AO01', 100, 'RELEASED', '0', 0)");
        // 工序（DISPATCHED）
        jdbc.update("INSERT INTO erp_mrp_mo_op (ID, MO_ID, OP_SEQ, OP_CODE, OP_NAME, OP_STATUS, "
                + "DEL_FLAG, VER_NO) VALUES ('ao-op1', 'ao-mo1', 10, 'OP-AO', '机加工', 'DISPATCHED', "
                + "'0', 0)");
        // 外协派工单
        jdbc.update("INSERT INTO erp_mrp_dispatch (ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, OP_NAME, "
                + "WC_CODE, WC_NAME, WC_TYPE, DURATION_HOURS, STATUS, OUTSOURCE_FLAG, PO_NO, "
                + "RECEIPT_FLAG, RECV_QTY, OS_SUPPLY_TYPE, DEL_FLAG, VER_NO) VALUES "
                + "('ao-d1', 'DPAO01', 'ao-mo1', 'MOAO01', 10, '机加工', 'OSWC', '外协中心', "
                + "'OUTSOURCED', 8, 'RELEASED', '1', 'POAO01', '0', 0, 'COMPANY_SUPPLY', '0', 0)");
        MoTestAuth.login("ao-planner", "ROLE_PLANNER");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM erp_mrp_output WHERE RPT_ID IN "
                + "(SELECT ID FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%')");
        jdbc.update("DELETE FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%'");
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE DISPATCH_NO LIKE 'DPAO%'");
        jdbc.update("DELETE FROM erp_mrp_mo_op WHERE MO_ID='ao-mo1'");
        jdbc.update("DELETE FROM erp_mrp_mo WHERE ID='ao-mo1'");
    }

    // ---------- 一次收足：自动报工 + 工序完成 ----------

    @Test
    void fullReceiptAutoReportsAndCompletesOp() {
        assertTrue(dispatchService.recallReceived("POAO01", "GRAO01", new BigDecimal("100")),
                "首次收货回联 true");
        // 派工单：RECEIPT_FLAG + RECV_QTY
        var d = jdbc.queryForMap("SELECT RECEIPT_FLAG, RECV_QTY FROM erp_mrp_dispatch "
                + "WHERE DISPATCH_NO='DPAO01'");
        assertEquals("1", d.get("RECEIPT_FLAG"));
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) d.get("RECV_QTY")));
        // 自动报工单（AO 前缀 + AUTO_OUTSOURCE 锚 + CONFIRMED）
        var rpt = jdbc.queryForMap("SELECT RPT_NO, STATUS, REMARK, RUN_HOURS, REPORTER "
                + "FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%'");
        assertTrue(String.valueOf(rpt.get("RPT_NO")).startsWith("AO"), "AO 前缀与人工 RP 区分");
        assertEquals("CONFIRMED", rpt.get("STATUS"), "自动报工直过 CONFIRMED");
        assertEquals("AUTO_OUTSOURCE:GRAO01", rpt.get("REMARK"), "幂等锚 = GR 单号");
        assertEquals("SYSTEM", rpt.get("REPORTER"));
        assertEquals(0, new BigDecimal("8").compareTo((BigDecimal) rpt.get("RUN_HOURS")),
                "工时 = 派工定额");
        // 产出行（合格 = 收货数）
        var out = jdbc.queryForMap("SELECT GOOD_QTY, NG_QTY FROM erp_mrp_output WHERE RPT_ID = "
                + "(SELECT ID FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%')");
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) out.get("GOOD_QTY")));
        // 工序完成
        String opStatus = jdbc.queryForObject(
                "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='ao-mo1' AND OP_SEQ=10",
                String.class);
        assertEquals("DONE", opStatus, "收足自动完成工序");
    }

    // ---------- 部分收货按次累计 ----------

    @Test
    void partialReceiptsAccumulateThenComplete() {
        dispatchService.recallReceived("POAO01", "GRAO01", new BigDecimal("60"));
        var d1 = jdbc.queryForMap("SELECT RECV_QTY FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPAO01'");
        assertEquals(0, new BigDecimal("60").compareTo((BigDecimal) d1.get("RECV_QTY")), "首次累计 60");
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='ao-mo1' AND OP_SEQ=10", String.class),
                "部分收货工序开工未完成");
        // 第二次收货 40 → 累计收足 100
        dispatchService.recallReceived("POAO01", "GRAO02", new BigDecimal("40"));
        var d2 = jdbc.queryForMap("SELECT RECV_QTY FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPAO01'");
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) d2.get("RECV_QTY")), "累计 100");
        assertEquals("DONE", jdbc.queryForObject(
                "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='ao-mo1' AND OP_SEQ=10", String.class),
                "收足后自动完成");
        // 两条自动报工（各 GR 一条）
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%'", Integer.class);
        assertEquals(2, cnt, "两次收货两条自动报工");
    }

    // ---------- 同 GR 幂等防重 ----------

    @Test
    void sameGrIdempotent() {
        dispatchService.recallReceived("POAO01", "GRAO01", new BigDecimal("50"));
        assertFalse(dispatchService.recallReceived("POAO01", "GRAO01", new BigDecimal("50")),
                "同 GR 二次过账幂等 false");
        var d = jdbc.queryForMap("SELECT RECV_QTY FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPAO01'");
        assertEquals(0, new BigDecimal("50").compareTo((BigDecimal) d.get("RECV_QTY")), "不重复累计");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%'", Integer.class);
        assertEquals(1, cnt, "不重复报工");
    }

    // ---------- 旧签名兼容：纯回联 ----------

    @Test
    void legacyRecallStillWorks() {
        assertTrue(dispatchService.recallReceived("POAO01"), "旧签名首次 true");
        assertFalse(dispatchService.recallReceived("POAO01"), "旧签名二次幂等 false");
        var d = jdbc.queryForMap("SELECT RECEIPT_FLAG FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPAO01'");
        assertEquals("1", d.get("RECEIPT_FLAG"));
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%'", Integer.class);
        assertEquals(0, cnt, "旧签名不触发自动报工");
    }

    // ---------- 非外协静默 ----------

    @Test
    void nonOutsourceSilent() {
        jdbc.update("UPDATE erp_mrp_dispatch SET OUTSOURCE_FLAG='0', PO_NO='PONON01' "
                + "WHERE DISPATCH_NO='DPAO01'");
        assertFalse(dispatchService.recallReceived("PONON01", "GRAO01", new BigDecimal("10")),
                "非外协静默 false");
    }

    // ---------- 4.2 红冲同步冲回 ----------

    @Test
    void redFlushReversesReportingAndRollsBackCompletion() {
        // 收足 100 → 报工+工序完成
        dispatchService.recallReceived("POAO01", "GRAO01", new BigDecimal("100"));
        assertEquals("DONE", jdbc.queryForObject(
                "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='ao-mo1' AND OP_SEQ=10", String.class),
                "收足后工序完成");
        // 红冲退 40 → 报工冲回、RECV_QTY 回拨、工序回退 RUNNING
        assertTrue(dispatchService.reverseAutoReport("POAO01", "RETAO01", new BigDecimal("40")),
                "红冲冲回 true");
        var d = jdbc.queryForMap("SELECT RECV_QTY FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPAO01'");
        assertEquals(0, new BigDecimal("60").compareTo((BigDecimal) d.get("RECV_QTY")), "RECV_QTY 回拨 60");
        var out = jdbc.queryForMap("SELECT GOOD_QTY FROM erp_mrp_output WHERE RPT_ID = "
                + "(SELECT ID FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%')");
        assertEquals(0, new BigDecimal("60").compareTo((BigDecimal) out.get("GOOD_QTY")), "合格产出冲回 60");
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='ao-mo1' AND OP_SEQ=10", String.class),
                "不再收足 → 工序回退 RUNNING");
        // 幂等：同退货单二次冲回不双冲
        assertFalse(dispatchService.reverseAutoReport("POAO01", "RETAO01", new BigDecimal("40")),
                "同退货单幂等 false");
        var d2 = jdbc.queryForMap("SELECT RECV_QTY FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPAO01'");
        assertEquals(0, new BigDecimal("60").compareTo((BigDecimal) d2.get("RECV_QTY")), "不重复回拨");
        var rpt = jdbc.queryForMap("SELECT REMARK FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%'");
        assertTrue(String.valueOf(rpt.get("REMARK")).contains("REVERSED:RETAO01"), "冲回锚留痕");
    }

    @Test
    void redFlushKeepsCompletionWhenStillFull() {
        dispatchService.recallReceived("POAO01", "GRAO01", new BigDecimal("100"));
        // 只退 20 → 累计 80 仍 < 计划 100，但本用例验证退量小于计划时仍回退（未收足口径）
        dispatchService.reverseAutoReport("POAO01", "RETAO02", new BigDecimal("20"));
        var d = jdbc.queryForMap("SELECT RECV_QTY FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPAO01'");
        assertEquals(0, new BigDecimal("80").compareTo((BigDecimal) d.get("RECV_QTY")), "回拨后 80");
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID='ao-mo1' AND OP_SEQ=10", String.class),
                "80 < 100 → 回退 RUNNING");
    }

    @Test
    void redFlushClampsGoodQtyAtZero() {
        dispatchService.recallReceived("POAO01", "GRAO01", new BigDecimal("30"));
        // 退 50 > 报工 30 → 合格产出钳 0，不为负
        dispatchService.reverseAutoReport("POAO01", "RETAO03", new BigDecimal("50"));
        var out = jdbc.queryForMap("SELECT GOOD_QTY FROM erp_mrp_output WHERE RPT_ID = "
                + "(SELECT ID FROM erp_mrp_reporting WHERE RPT_NO LIKE 'AO%')");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) out.get("GOOD_QTY")), "GOOD_QTY 钳 0 不为负");
        var d = jdbc.queryForMap("SELECT RECV_QTY FROM erp_mrp_dispatch WHERE DISPATCH_NO='DPAO01'");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) d.get("RECV_QTY")), "RECV_QTY 钳 0 不为负");
    }

    @Test
    void redFlushNonOutsourceSilent() {
        jdbc.update("UPDATE erp_mrp_dispatch SET OUTSOURCE_FLAG='0', PO_NO='PONON01' "
                + "WHERE DISPATCH_NO='DPAO01'");
        assertFalse(dispatchService.reverseAutoReport("PONON01", "RETAO04", new BigDecimal("10")),
                "非外协红冲静默 false");
    }
}
