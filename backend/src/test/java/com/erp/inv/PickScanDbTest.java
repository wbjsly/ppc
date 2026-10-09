package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.PickTask;
import com.erp.service.inv.PickDiffService;
import com.erp.service.inv.PickScanService;
import com.erp.service.inv.PickTaskService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行级扫码确认与短少差异 DB 语义（真实 MySQL，spec picking-review 行级扫码确认 /
 * 实拣差异生成与预留释放，任务 3.1/3.2/3.3）：
 * 三码不符 422+FAIL 记录（批次不符附带 BATCH 差异）、放行后确认到任务 PICKED、
 * 实拣短少 422→registerShort 落 QTY 差异+DIFF_PENDING、销售单差额释放预留、
 * 序列物料校验、无放行确认 422。
 */
@SpringBootTest
class PickScanDbTest {

    private static final String DOC = "PSC-MI-01";
    private static final String ITEM = "IT-PSC-01";
    private static final String SN_ITEM = "IT-PSC-SN";

    @Autowired
    private PickTaskService taskService;
    @Autowired
    private PickScanService scanService;
    @Autowired
    private PickDiffService diffService;
    @Autowired
    private JdbcTemplate jdbc;

    private String taskId;

    @BeforeEach
    void setup() {
        purge();
        asWarehouse();
        // 物料 + 库存
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                        + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                        + "STATUS, CREATE_BY) VALUES ('psc-it-1', ?, '扫码测试物料', '0001', 'PC', "
                        + "'STRUCT', 'BUY', 'NORMAL', '1', '0', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) "
                        + "VALUES ('psc-st-1','WH-MAIN',?,'扫码测试物料','PSC-B1','MAIN-1',30,0,0,30,"
                        + "'2026-10-01','junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, "
                        + "EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                        + "('psc-lg-1','PSC-B1',?,'扫码测试物料','2026-01-01','2027-12-31','0','1','junit')",
                ITEM);
        // 领料单 + 行
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('psc-mi-1', ?, 'OWN', "
                        + "'WO-PSC-01', 'DRAFT', 'junit', '0', 0)", DOC);
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                        + "ITEM_NAME, BATCH_NO, BIN_CODE, QTY, STOCK_TYPE, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('psc-mi-l1','psc-mi-1',1,?,'扫码测试物料','PSC-B1','MAIN-1',30,'OWN',"
                        + "'junit','0',0)", ITEM);
        // 任务
        PickTask t = taskService.createFromDoc("MATERIAL_OUT", DOC);
        taskId = t.getId();
        taskService.transition(taskId, PickTask.ST_CREATED, PickTask.ST_PICKING);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_pick_scan_log WHERE TASK_ID IN "
                + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO IN (?, 'PSC-SH-01'))", DOC);
        jdbc.update("DELETE FROM erp_inv_check_diff WHERE SRC_DOC_NO IN (?, 'PSC-SH-01') "
                + "OR ITEM_CODE IN (?, ?)", DOC, ITEM, SN_ITEM);
        jdbc.update("DELETE FROM erp_pick_task_line WHERE TASK_ID IN "
                + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO IN (?, 'PSC-SH-01'))", DOC);
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO IN (?, 'PSC-SH-01', 'PSC-MI-SN')", DOC);
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE IN (?, ?)", ITEM, SN_ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_NO IN (?, 'PSC-MI-SN')", DOC);
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE SHIP_ID = 'psc-sh-1'");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID = 'psc-sh-1'");
        jdbc.update("DELETE FROM erp_sd_reservation WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE IN (?, ?)", ITEM, SN_ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE IN (?, ?)", ITEM, SN_ITEM);
        jdbc.update("DELETE FROM erp_inv_serial WHERE ITEM_CODE = ?", SN_ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE IN (?, ?) OR ID IN "
                + "('psc-it-1', 'psc-it-sn')", ITEM, SN_ITEM);
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }

    /** 仓位码不符 → 422 + FAIL 记录，行未放行（BR-4.4-25） */
    @Test
    void wrongBinBlockedWithFailLog() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scanService.verify(taskId, 1, "WRONG-BIN", ITEM, "PSC-B1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("仓位"), ex.getMessage());
        int fails = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_pick_scan_log "
                + "WHERE TASK_ID = ? AND RESULT = 'FAIL'", Integer.class, taskId);
        assertEquals(1, fails, "失败明细已落记录");
        String flag = jdbc.queryForObject("SELECT SCAN_OK_FLAG FROM erp_pick_task_line "
                + "WHERE TASK_ID = ?", String.class, taskId);
        assertEquals("0", flag, "未放行");
    }

    /** 批次不符 → 422 + FAIL + BATCH 差异行 + 任务 DIFF_PENDING（BR-4.4-26） */
    @Test
    void wrongBatchBlockedWithDiff() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scanService.verify(taskId, 1, "MAIN-1", ITEM, "OTHER-BATCH"));
        assertEquals(422, ex.getCode());
        int diffs = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_check_diff "
                + "WHERE SRC_TASK_ID = ? AND DIFF_KIND = 'BATCH'", Integer.class, taskId);
        assertEquals(1, diffs, "批次差异行已登记");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_pick_task WHERE ID = ?",
                String.class, taskId);
        assertEquals(PickTask.ST_DIFF_PENDING, st, "任务挂分支");
    }

    /** 三码全匹配放行 → 确认到任务 PICKED（spec 场景：全匹配放行） */
    @Test
    void allMatchConfirmToPicked() {
        scanService.verify(taskId, 1, "MAIN-1", ITEM, "PSC-B1");
        scanService.confirmLine(taskId, 1, new BigDecimal("30"), null);

        Map<String, Object> d = taskService.detail(taskId);
        var line = (com.erp.entity.inv.PickTaskLine)
                ((java.util.List<?>) d.get("lines")).get(0);
        assertEquals(com.erp.entity.inv.PickTaskLine.LS_PICKED, line.getLineStatus());
        var task = (PickTask) d.get("task");
        assertEquals(PickTask.ST_PICKED, task.getStatus(), "全行 PICKED → 任务 PICKED");
        int passes = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_pick_scan_log "
                + "WHERE TASK_ID = ? AND SCAN_TYPE = 'PASS'", Integer.class, taskId);
        assertEquals(1, passes, "行放行标记已落记录");
    }

    /** 未放行确认 422（spec 场景隐含：先校验后确认） */
    @Test
    void confirmWithoutVerifyRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scanService.confirmLine(taskId, 1, new BigDecimal("30"), null));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("码校验"), ex.getMessage());
    }

    /** 实拣短少确认 422 → registerShort 落 QTY 差异 + 行 SHORT（BR-4.4-28） */
    @Test
    void shortConfirmRejectedThenRegisterShort() {
        scanService.verify(taskId, 1, "MAIN-1", ITEM, "PSC-B1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scanService.confirmLine(taskId, 1, new BigDecimal("28"), null));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("短少差异"), ex.getMessage());

        Map<String, Object> out = scanService.registerShort(taskId, 1,
                new BigDecimal("28"), "现场短少待复盘");
        var diff = (com.erp.entity.inv.InvCheckDiff) out.get("diff");
        assertEquals("QTY", diff.getDiffKind());
        assertEquals(0, new BigDecimal("28").compareTo(diff.getActualQty()));

        String st = jdbc.queryForObject("SELECT STATUS FROM erp_pick_task WHERE ID = ?",
                String.class, taskId);
        assertEquals(PickTask.ST_DIFF_PENDING, st);
        String ls = jdbc.queryForObject("SELECT LINE_STATUS FROM erp_pick_task_line "
                + "WHERE TASK_ID = ?", String.class, taskId);
        assertEquals("SHORT", ls);
    }

    /** 销售单短少 → 差额释放 ATP 预留（spec 场景：短少生成差异并释放预留） */
    @Test
    void salesShortReleasesReservation() {
        // 夹具：发货行 + SO 行 ACTIVE 预留 30
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, "
                + "CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, TOTAL_AMT, CREATE_BY) VALUES "
                + "('psc-sh-1','PSC-SH-01','STANDARD','psc-c','PSC客户','WH-MAIN','DRAFT',30,300,'junit')");
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, SO_LINE_ID, "
                + "ITEM_CODE, ITEM_NAME, QTY, WAREHOUSE_CODE, LINE_STATUS, CREATE_BY) VALUES "
                + "('psc-sh-l1','psc-sh-1',1,'psc-so','psc-sol-1',?,'扫码测试物料',30,'WH-MAIN',"
                + "'PENDING','junit')", ITEM);
        jdbc.update("INSERT INTO erp_sd_reservation (ID, SO_ID, SO_NO, LINE_ID, LINE_NO, ITEM_CODE, "
                + "WAREHOUSE_CODE, BATCH_NO, QTY, STATUS, LOCK_AT, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('psc-rsv-1','psc-so','PSC-SO-1','psc-sol-1',1,?,'WH-MAIN','PSC-B1',30,'ACTIVE',"
                + "NOW(),'junit','0',0)", ITEM);

        PickTask st = taskService.createFromDoc("SALES_OUT", "PSC-SH-01");
        taskService.transition(st.getId(), PickTask.ST_CREATED, PickTask.ST_PICKING);

        // 行 srcLineId = 发货行 ID → registerShort(30→28) 释放差额 2
        scanService.registerShort(st.getId(), 1, new BigDecimal("28"), "销售短少 2");

        BigDecimal active = jdbc.queryForObject("SELECT COALESCE(SUM(QTY),0) "
                + "FROM erp_sd_reservation WHERE LINE_ID = 'psc-sol-1' AND STATUS = 'ACTIVE'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("28").compareTo(active), "预留 30 → 释放差额 2 = 28");
        // 部分释放走缩减分支（QTY 30→28 + releaseReason），不产生 RELEASED 行
        String reason = jdbc.queryForObject("SELECT RELEASE_REASON FROM erp_sd_reservation "
                + "WHERE LINE_ID = 'psc-sol-1'", String.class);
        assertTrue(reason != null && reason.contains("部分释放"),
                "释放原因留痕，实际：" + reason);
    }

    /** 序列物料：无序列 422、假序列 422、真序列放行（C-4.4-02） */
    @Test
    void serialValidation() {
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                        + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                        + "STATUS, CREATE_BY) VALUES ('psc-it-sn',?,'序列扫码物料','0001','PC',"
                        + "'STRUCT','BUY','NORMAL','1','1','1','junit')", SN_ITEM);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) "
                        + "VALUES ('psc-st-sn','WH-MAIN',?,'序列扫码物料','PSC-B1','MAIN-1',1,0,0,1,"
                        + "'2026-10-01','junit')", SN_ITEM);
        jdbc.update("INSERT INTO erp_inv_serial (ID, SERIAL_NO, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('psc-sn-1','PSC-SN-001',?,"
                + "'序列扫码物料','PSC-B1','IN_STOCK','junit','0',0)", SN_ITEM);
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('psc-mi-sn','PSC-MI-SN','OWN',"
                        + "'WO-PSC-2','DRAFT','junit','0',0)");
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                        + "ITEM_NAME, BATCH_NO, BIN_CODE, QTY, STOCK_TYPE, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('psc-mi-sn-l','psc-mi-sn',1,?,'序列扫码物料','PSC-B1','MAIN-1',1,'OWN',"
                        + "'junit','0',0)", SN_ITEM);
        PickTask t = taskService.createFromDoc("MATERIAL_OUT", "PSC-MI-SN");
        taskService.transition(t.getId(), PickTask.ST_CREATED, PickTask.ST_PICKING);
        scanService.verify(t.getId(), 1, "MAIN-1", SN_ITEM, "PSC-B1");

        ServiceException e1 = assertThrows(ServiceException.class,
                () -> scanService.confirmLine(t.getId(), 1, new BigDecimal("1"), null));
        assertEquals(422, e1.getCode(), "无序列 422");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> scanService.confirmLine(t.getId(), 1, new BigDecimal("1"), "FAKE-SN"));
        assertEquals(422, e2.getCode(), "假序列 422");
        scanService.confirmLine(t.getId(), 1, new BigDecimal("1"), "PSC-SN-001");
        var task = (PickTask) taskService.detail(t.getId()).get("task");
        assertEquals(PickTask.ST_PICKED, task.getStatus());
    }

    /** 差异闭环回迁：DIFF_PENDING + 未复核行 → PICKING（spec 场景：闭环解锁） */
    @Test
    void closeRevertsToPicking() {
        scanService.verify(taskId, 1, "MAIN-1", ITEM, "PSC-B1");
        scanService.registerShort(taskId, 1, new BigDecimal("28"), "短少 2");
        String diffId = jdbc.queryForObject("SELECT ID FROM erp_inv_check_diff "
                + "WHERE SRC_TASK_ID = ?", String.class, taskId);

        diffService.close(diffId, "补拣完成 28+2");

        String st = jdbc.queryForObject("SELECT STATUS FROM erp_pick_task WHERE ID = ?",
                String.class, taskId);
        assertEquals(PickTask.ST_PICKING, st, "有未复核行 → 回 PICKING");
        String ds = jdbc.queryForObject("SELECT STATUS FROM erp_inv_check_diff WHERE ID = ?",
                String.class, diffId);
        assertEquals("RESOLVED", ds);
    }

    /** 闭环原因必填 422（spec 场景：闭环原因必填） */
    @Test
    void closeRequiresNote() {
        scanService.verify(taskId, 1, "MAIN-1", ITEM, "PSC-B1");
        scanService.registerShort(taskId, 1, new BigDecimal("28"), "短少");
        String diffId = jdbc.queryForObject("SELECT ID FROM erp_inv_check_diff "
                + "WHERE SRC_TASK_ID = ?", String.class, taskId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> diffService.close(diffId, " "));
        assertEquals(422, ex.getCode());
    }
}
