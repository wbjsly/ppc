package com.erp.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.inv.InvCountLine;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.CountInputService;
import com.erp.service.inv.CountTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 盘点全链 DB 语义（真实 MySQL，spec count-management，任务 10.1）：
 * 任务生成账面定格与遮蔽、>10% 阻断待复盘、容差内自动调整（含凭证）、
 * 超容差挂 CountDiff 审批通过自动调整 + 凭证、驳回回待复盘、负库存调整被引擎拦。
 */
@SpringBootTest
class CountManagementDbTest {

    private static final String WH = "WH-COUNTM";
    private static final String ITEM = "IT-COUNTM-01";

    @Autowired
    private CountTaskService taskService;
    @Autowired
    private CountInputService inputService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String taskId;

    @BeforeEach
    void setup() {
        cleanup();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STANDARD_COST, STATUS, CREATE_BY) VALUES ('cm-it-01', ?, '盘点管理测试物料', "
                + "'0001', 'PCS', 'G001', 'BUY', 'NORMAL', '1', '0', 10, '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY, DEL_FLAG, "
                + "VER_NO) VALUES ('cm-led-1', 'CM-B1', ?, '盘点管理测试物料', ?, ?, '0', '1', "
                + "'junit', '0', 0)", ITEM,
                LocalDate.now().minusDays(60).toString(),
                LocalDate.now().plusDays(300).toString());
        // 两仓位各 100（BIN-A01 将盘亏 5 / BIN-B01 将盘盈 3）
        seedStock("cm-stk-a", "BIN-A01", "100");
        seedStock("cm-stk-b", "BIN-B01", "100");
        login("wh-cm", "ROLE_WAREHOUSE");
    }

    @AfterEach
    void afterEach() {
        cleanup();
        SecurityContextHolder.clearContext();
    }

    private void cleanup() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a "
                + "ON t.APPR_ID = a.ID WHERE a.BIZ_TYPE = 'CountDiff'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'CountDiff'");
        jdbc.update("DELETE FROM erp_inv_check_diff WHERE SRC_DOC_TYPE = 'COUNT_TASK'");
        jdbc.update("DELETE FROM erp_inv_count_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_count_task WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_transaction WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'COUNT_ADJUST'");
    }

    private void seedStock(String id, String bin, String qty) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                + "BATCH_NO, BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, "
                + "CREATE_BY) VALUES (?, ?, ?, '盘点管理测试物料', 'CM-B1', ?, ?, 0, 0, ?, "
                + "'2026-10-01', 'junit')", id, WH, ITEM, bin, qty, qty);
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private String lineIdOf(String bin) {
        return jdbc.queryForObject("SELECT ID FROM erp_inv_count_line WHERE TASK_ID = ? "
                + "AND BIN_CODE = ?", String.class, taskId, bin);
    }

    private Map<String, Object> lineRow(String bin) {
        return jdbc.queryForMap("SELECT * FROM erp_inv_count_line WHERE TASK_ID = ? "
                + "AND BIN_CODE = ?", taskId, bin);
    }

    private BigDecimal stockQty(String bin) {
        return jdbc.queryForObject("SELECT QTY FROM erp_inv_stock WHERE WAREHOUSE_CODE = ? "
                + "AND ITEM_CODE = ? AND BIN_CODE = ?", BigDecimal.class, WH, ITEM, bin);
    }

    private ApprovalTask activeTask(String apprId) {
        return taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, apprId)
                .eq(ApprovalTask::getStatus, "ACTIVE"));
    }

    /** 任务生成：账面定格 + 行列表遮蔽（PENDING 行无 bookQty） */
    @Test
    void createTaskSnapshotsBookAndMasks() {
        Map<String, Object> out = taskService.createFull(WH, "junit 全盘");
        taskId = String.valueOf(out.get("id"));
        assertEquals(2, out.get("totalLines"), "两仓位维度");

        List<Map<String, Object>> lines = taskService.lines(taskId, null, null);
        assertEquals(2, lines.size());
        for (Map<String, Object> l : lines) {
            assertTrue(!l.containsKey("bookQty"), "PENDING 行遮蔽账面（FR-4.4-6-3）");
        }
        // DB 侧账面已定格 100
        assertEquals(0, new BigDecimal("100").compareTo(
                (BigDecimal) lineRow("BIN-A01").get("BOOK_QTY")));
    }

    /** >10% 阻断标待复盘（BR-4.4-39 L1 保留） */
    @Test
    void overTenPercentBlocksAsRecount() {
        Map<String, Object> out = taskService.createFull(WH, null);
        taskId = String.valueOf(out.get("id"));

        ServiceException e = assertThrows(ServiceException.class, () ->
                inputService.submitCount(lineIdOf("BIN-A01"), new BigDecimal("115"), null, null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("待复盘"), e.getMessage());
        assertEquals("RECOUNT", String.valueOf(lineRow("BIN-A01").get("COUNT_STATUS")));
        assertEquals(0, new BigDecimal("100").compareTo(stockQty("BIN-A01")), "无库存变动");

        // 重录 102（2% 超容差但 <10%）→ 可提交
        Map<String, Object> res = inputService.submitCount(lineIdOf("BIN-A01"),
                new BigDecimal("102"), null, null);
        assertEquals("PENDING_APPROVAL", res.get("outcome"), "重录清除 RECOUNT 并走审批");
        assertEquals("COUNTED", String.valueOf(lineRow("BIN-A01").get("COUNT_STATUS")));
    }

    /** 容差内自动调整：账面 100 实盘 100.3（0.3% ≤0.5%）→ 当场 ADJUSTED + 凭证 */
    @Test
    void withinToleranceAutoAdjustsWithVoucher() {
        Map<String, Object> out = taskService.createFull(WH, null);
        taskId = String.valueOf(out.get("id"));

        Map<String, Object> res = inputService.submitCount(lineIdOf("BIN-A01"),
                new BigDecimal("100.3"), null, null);
        assertEquals("AUTO_ADJUSTED", res.get("outcome"));
        assertEquals(0, new BigDecimal("100.3").compareTo(stockQty("BIN-A01")),
                "库存调至实盘");
        assertEquals("ADJUSTED", String.valueOf(lineRow("BIN-A01").get("COUNT_STATUS")));

        Integer vouchers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE = 'COUNT_ADJUST'",
                Integer.class);
        assertTrue(vouchers >= 1, "容差内调整生成凭证");
    }

    /** 超容差：COUNT 差异单 + CountDiff 审批通过 → 自动调整 + 凭证 → 任务 DONE */
    @Test
    void overToleranceApprovalAdjustsAndCompletes() {
        Map<String, Object> out = taskService.createFull(WH, null);
        taskId = String.valueOf(out.get("id"));

        // BIN-A01 盘亏 5（5% 超容差）
        Map<String, Object> res = inputService.submitCount(lineIdOf("BIN-A01"),
                new BigDecimal("95"), null, "盘亏待审");
        assertEquals("PENDING_APPROVAL", res.get("outcome"));
        assertEquals("ADJUSTING", String.valueOf(
                jdbc.queryForObject("SELECT STATUS FROM erp_inv_count_task WHERE ID = ?",
                        String.class, taskId)));
        assertEquals(0, new BigDecimal("100").compareTo(stockQty("BIN-A01")), "审批前未调整");
        Integer cntDiffs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_check_diff WHERE DIFF_TYPE = 'COUNT'",
                Integer.class);
        assertEquals(1, cntDiffs, "COUNT 差异单生成");

        // BIN-B01 录一致（另一行闭环前置）
        inputService.submitCount(lineIdOf("BIN-B01"), new BigDecimal("100"), null, null);

        // 仓库主管签署通过
        String diffId = jdbc.queryForObject(
                "SELECT ID FROM erp_inv_check_diff WHERE DIFF_TYPE = 'COUNT' LIMIT 1",
                String.class);
        String apprId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE = 'CountDiff' AND BIZ_ID = ?",
                String.class, diffId);
        ApprovalTask t = activeTask(apprId);
        assertNotNull(t, "待签节点存在");
        login("wh-cm2", "ROLE_WAREHOUSE");
        approvalEngine.pass(t.getId(), "同意调整");

        assertEquals(0, new BigDecimal("95").compareTo(stockQty("BIN-A01")),
                "审批通过自动调整");
        assertEquals("ADJUSTED", String.valueOf(lineRow("BIN-A01").get("COUNT_STATUS")));
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_check_diff WHERE ID = ?", String.class, diffId));
        Integer vouchers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE = 'COUNT_ADJUST'",
                Integer.class);
        assertTrue(vouchers >= 1, "审批调整生成凭证");
        // 全部行闭环 → DONE + 报告
        assertEquals("DONE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_count_task WHERE ID = ?", String.class, taskId));
        String report = jdbc.queryForObject(
                "SELECT REPORT_JSON FROM erp_inv_count_task WHERE ID = ?", String.class, taskId);
        assertNotNull(report, "报告物化");
        assertTrue(report.contains("watchBins"), "报告含监控清单结构");
    }

    /** 驳回：差异单回 PENDING、行标 RECOUNT、任务回 COUNTING */
    @Test
    void rejectReturnsToRecount() {
        Map<String, Object> out = taskService.createFull(WH, null);
        taskId = String.valueOf(out.get("id"));
        inputService.submitCount(lineIdOf("BIN-A01"), new BigDecimal("95"), null, null);

        String diffId = jdbc.queryForObject(
                "SELECT ID FROM erp_inv_check_diff WHERE DIFF_TYPE = 'COUNT' LIMIT 1",
                String.class);
        String apprId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE = 'CountDiff' AND BIZ_ID = ?",
                String.class, diffId);
        ApprovalTask t = activeTask(apprId);
        login("wh-cm2", "ROLE_WAREHOUSE");
        approvalEngine.reject(t.getId(), "证据不足退回");

        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_check_diff WHERE ID = ?", String.class, diffId));
        assertEquals("RECOUNT", String.valueOf(lineRow("BIN-A01").get("COUNT_STATUS")));
        assertEquals("COUNTING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_count_task WHERE ID = ?", String.class, taskId));
        assertEquals(0, new BigDecimal("100").compareTo(stockQty("BIN-A01")), "驳回无调整");
    }

    /** 负库存调整被引擎拦（C-4.4-01/BR-4.4-40）：账面被并发出库掏空后调整回滚 */
    @Test
    void negativeAdjustBlockedByEngine() {
        Map<String, Object> out = taskService.createFull(WH, null);
        taskId = String.valueOf(out.get("id"));
        // 把 BIN-A01 在手掏到 0.2（模拟并发出库），账面定格仍 100
        jdbc.update("UPDATE erp_inv_stock SET AVAILABLE_QTY = 0.2, QTY = 0.2 "
                + "WHERE WAREHOUSE_CODE = ? AND ITEM_CODE = ? AND BIN_CODE = ?",
                WH, ITEM, "BIN-A01");
        // 实盘 99.6 → 差异率 0.4% ≤0.5%（走容差内自动调整），盘亏 0.4 > 在手 0.2 → 引擎负库存阻断
        ServiceException e = assertThrows(ServiceException.class, () ->
                inputService.submitCount(lineIdOf("BIN-A01"), new BigDecimal("99.6"), null, null));
        assertEquals(422, e.getCode(), "负库存阻断");
        assertTrue(e.getMessage().contains("库存不足") || e.getMessage().contains("可用量"),
                e.getMessage());
        // 整事务回滚：行未 ADJUSTED（回 PENDING）、库存未动、无凭证残留
        org.junit.jupiter.api.Assertions.assertNotEquals("ADJUSTED",
                String.valueOf(lineRow("BIN-A01").get("COUNT_STATUS")));
        assertEquals(0, new BigDecimal("0.2").compareTo(stockQty("BIN-A01")), "库存未动");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE = 'COUNT_ADJUST'",
                Integer.class), "无凭证残留");
    }

    /** 权限：质量角色不可提交实盘 */
    @Test
    void qualityRoleForbidden() {
        Map<String, Object> out = taskService.createFull(WH, null);
        taskId = String.valueOf(out.get("id"));
        login("q-cm", "ROLE_QUALITY_ENG");
        ServiceException e = assertThrows(ServiceException.class, () ->
                inputService.submitCount(lineIdOf("BIN-A01"), new BigDecimal("100"), null, null));
        assertEquals(403, e.getCode());
    }
}
