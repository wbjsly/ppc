package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.PickTask;
import com.erp.service.inv.OutboundWorkbenchService;
import com.erp.service.inv.PickDiffService;
import com.erp.service.inv.PickTaskService;
import com.erp.service.inv.ScrapOrderService;
import com.erp.service.inv.TransferOrderService;
import com.erp.service.sd.ShipmentService;
import com.erp.service.vmi.MaterialIssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出库过账门闩与任务联动 DB 语义（真实 MySQL，spec picking-review 全入口过账门闩，
 * 任务 5.2）：PENDING 差异 → 领料页与作业台（委托同动作）均 422 无库存变动；
 * 销售/调拨/报废三入口同样 422（全入口覆盖）；闭环后放行、过账成功任务 DONE→COMPLETED；
 * 无任务单据不受影响。
 */
@SpringBootTest
class PickGateDbTest {

    private static final String DOC = "GATE-MI-01";
    private static final String ITEM = "IT-GATE-01";

    @Autowired
    private MaterialIssueService issueService;
    @Autowired
    private OutboundWorkbenchService workbenchService;
    @Autowired
    private ShipmentService shipmentService;
    @Autowired
    private TransferOrderService transferService;
    @Autowired
    private ScrapOrderService scrapService;
    @Autowired
    private PickTaskService taskService;
    @Autowired
    private PickDiffService diffService;
    @Autowired
    private JdbcTemplate jdbc;

    private String taskId;

    @BeforeEach
    void setup() {
        purge();
        asWarehouse();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                        + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                        + "STATUS, CREATE_BY) VALUES ('gate-it-1', ?, '门闩测试物料', '0001', 'PC', "
                        + "'STRUCT', 'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) "
                        + "VALUES ('gate-st-1','WH-MAIN',?,'门闩测试物料','GATE-B1','',100,0,0,100,"
                        + "'2026-10-01','junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, "
                        + "EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                        + "('gate-lg-1','GATE-B1',?,'门闩测试物料','2026-01-01','2027-12-31','0','1','junit')",
                ITEM);
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('gate-mi-1', ?, 'OWN', "
                        + "'WO-GATE-01', 'DRAFT', 'junit', '0', 0)", DOC);
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                        + "ITEM_NAME, BATCH_NO, QTY, STOCK_TYPE, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                        + "('gate-mi-l1','gate-mi-1',1,?,'门闩测试物料','GATE-B1',30,'OWN','junit','0',0)",
                ITEM);
        PickTask t = taskService.createFromDoc("MATERIAL_OUT", DOC);
        taskId = t.getId();
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_pick_scan_log WHERE TASK_ID IN "
                + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO IN (?, 'GATE-SH-01'))", DOC);
        jdbc.update("DELETE FROM erp_inv_check_diff WHERE SRC_DOC_NO IN "
                + "(?, 'GATE-SH-01', 'GATE-TR-01', 'GATE-SC-01') OR ITEM_CODE = ?", DOC, ITEM);
        jdbc.update("DELETE FROM erp_pick_task_line WHERE TASK_ID IN "
                + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO IN (?, 'GATE-SH-01'))", DOC);
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO IN (?, 'GATE-SH-01')", DOC);
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_NO = ?", DOC);
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_sd_shipment WHERE SHIP_NO = 'GATE-SH-01'");
        jdbc.update("DELETE FROM erp_inv_transfer_order_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_transfer_order WHERE TRANSFER_NO = 'GATE-TR-01'");
        jdbc.update("DELETE FROM erp_inv_scrap_order_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_scrap_order WHERE SCRAP_NO = 'GATE-SC-01'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_transaction WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_ops_outbox WHERE SOURCE IN (?, 'GATE-SH-01', "
                + "'GATE-TR-01', 'GATE-SC-01')", DOC);
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }

    /** 造 PENDING 拣货差异（门闩只读差异表） */
    private void seedPendingDiff(String type, String docNo) {
        jdbc.update("INSERT INTO erp_inv_check_diff (ID, DIFF_TYPE, SRC_TASK_ID, SRC_DOC_TYPE, "
                        + "SRC_DOC_NO, LINE_NO, ITEM_CODE, WAREHOUSE_CODE, BATCH_NO, DIFF_KIND, "
                        + "EXPECT_QTY, ACTUAL_QTY, DELTA_QTY, STATUS, DIFF_NOTE, CREATE_BY, DEL_FLAG, "
                        + "VER_NO) VALUES (?, 'PICK', ?, ?, ?, 1, ?, 'WH-MAIN', 'GATE-B1', 'QTY', "
                        + "30, 28, -2, 'PENDING', '门闩夹具', 'junit', '0', 0)",
                "gd-" + docNo, taskId, type, docNo, ITEM);
    }

    private String diffId(String docNo) {
        return jdbc.queryForObject("SELECT ID FROM erp_inv_check_diff WHERE SRC_DOC_NO = ?",
                String.class, docNo);
    }

    /** 领料页入口：PENDING → 422 无库存变动（spec 场景：差异未闭环拦截过账） */
    @Test
    void materialIssueGate422() {
        seedPendingDiff("MATERIAL_OUT", DOC);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> issueService.post("gate-mi-1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("未闭环拣货差异"), ex.getMessage());
        BigDecimal qty = jdbc.queryForObject("SELECT QTY FROM erp_inv_stock WHERE ID = 'gate-st-1'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("100").compareTo(qty), "库存无变动");
        int txns = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_transaction "
                + "WHERE ITEM_CODE = ?", Integer.class, ITEM);
        assertEquals(0, txns, "无流水");
    }

    /** 作业台委托入口：同一后端动作同样 422（spec 场景：两入口一致） */
    @Test
    void workbenchGate422() {
        seedPendingDiff("MATERIAL_OUT", DOC);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> workbenchService.post("MATERIAL_OUT", "gate-mi-1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("未闭环拣货差异"), ex.getMessage());
    }

    /** 销售入口 422（doPost 门闩先行，库存不动） */
    @Test
    void salesGate422() {
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, "
                        + "CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, TOTAL_AMT, CREATE_BY) "
                        + "VALUES ('gate-sh-1','GATE-SH-01','EXCHANGE','gate-c','门闩客户','WH-MAIN',"
                        + "'DRAFT',30,300,'junit')");
        jdbc.update("INSERT INTO erp_sd_shipment_line (ID, SHIP_ID, LINE_NO, SO_ID, SO_LINE_ID, "
                        + "ITEM_CODE, ITEM_NAME, QTY, WAREHOUSE_CODE, LINE_STATUS, CREATE_BY) VALUES "
                        + "('gate-sh-l1','gate-sh-1',1,'gate-so','gate-sol',?,'门闩测试物料',30,"
                        + "'WH-MAIN','PENDING','junit')", ITEM);
        seedPendingDiff("SALES_OUT", "GATE-SH-01");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> shipmentService.post("gate-sh-1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("未闭环拣货差异"), ex.getMessage());
        BigDecimal qty = jdbc.queryForObject("SELECT QTY FROM erp_inv_stock WHERE ID = 'gate-st-1'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("100").compareTo(qty), "销售库存无变动");
    }

    /** 调拨入口 422 */
    @Test
    void transferGate422() {
        jdbc.update("INSERT INTO erp_inv_transfer_order (ID, TRANSFER_NO, OUT_WH_CODE, IN_WH_CODE, "
                        + "OUT_LE_CODE, IN_LE_CODE, CROSS_LE, STATUS, TOTAL_QTY, TOTAL_AMOUNT, "
                        + "REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('gate-tr-1','GATE-TR-01',"
                        + "'WH-MAIN','WH-02','LE-0001','LE-0001','0','DRAFT',30,150,'GATE','junit','0',0)");
        seedPendingDiff("TRANSFER_OUT", "GATE-TR-01");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> transferService.postOut("gate-tr-1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("未闭环拣货差异"), ex.getMessage());
        BigDecimal qty = jdbc.queryForObject("SELECT QTY FROM erp_inv_stock WHERE ID = 'gate-st-1'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("100").compareTo(qty), "调拨库存无变动");
    }

    /** 报废入口 422（状态就绪后撞门闩） */
    @Test
    void scrapGate422() {
        jdbc.update("INSERT INTO erp_inv_scrap_order (ID, SCRAP_NO, WAREHOUSE_CODE, REASON, "
                        + "STATUS, TOTAL_QTY, TOTAL_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                        + "('gate-sc-1','GATE-SC-01','WH-MAIN','OTHER','APPROVED',30,300,'junit','0',0)");
        seedPendingDiff("SCRAP_OUT", "GATE-SC-01");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scrapService.post("gate-sc-1"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("未闭环拣货差异"), ex.getMessage());
        BigDecimal qty = jdbc.queryForObject("SELECT QTY FROM erp_inv_stock WHERE ID = 'gate-st-1'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("100").compareTo(qty), "报废库存无变动");
    }

    /** 闭环后放行 + 过账成功任务 DONE → COMPLETED（spec 场景：闭环解锁过账/过账成功联动） */
    @Test
    void closeUnlocksAndPostingCompletesTask() {
        seedPendingDiff("MATERIAL_OUT", DOC);
        assertThrows(ServiceException.class, () -> issueService.post("gate-mi-1"));

        // 任务推进到 DONE（复核通过口径），再闭环差异（顺序无依赖：门闩只看差异表）
        taskService.transition(taskId, PickTask.ST_CREATED, PickTask.ST_PICKING);
        taskService.transition(taskId, PickTask.ST_PICKING, PickTask.ST_PICKED);
        taskService.transition(taskId, PickTask.ST_PICKED, PickTask.ST_REVIEWING);
        taskService.transition(taskId, PickTask.ST_REVIEWING, PickTask.ST_DONE);
        diffService.close(diffId(DOC), "补拣复核通过");

        // 门闩解除 → 过账成功 → 任务 COMPLETED
        issueService.post("gate-mi-1");
        String docSt = jdbc.queryForObject("SELECT STATUS FROM erp_inv_material_issue "
                + "WHERE ID = 'gate-mi-1'", String.class);
        assertEquals("POSTED", docSt, "领料单已过账");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_pick_task WHERE ID = ?",
                String.class, taskId);
        assertEquals(PickTask.ST_COMPLETED, st, "过账成功联动 COMPLETED");
        BigDecimal qty = jdbc.queryForObject("SELECT QTY FROM erp_inv_stock WHERE ID = 'gate-st-1'",
                BigDecimal.class);
        assertEquals(0, new BigDecimal("70").compareTo(qty), "100-30=70");
    }

    /** 无差异单据过账不受影响（spec 场景：无任务单据不受影响） */
    @Test
    void noDiffDocUnaffected() {
        issueService.post("gate-mi-1");
        String docSt = jdbc.queryForObject("SELECT STATUS FROM erp_inv_material_issue "
                + "WHERE ID = 'gate-mi-1'", String.class);
        assertEquals("POSTED", docSt);
    }
}
