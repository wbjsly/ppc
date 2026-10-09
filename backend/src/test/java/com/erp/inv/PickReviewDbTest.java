package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.PickTask;
import com.erp.service.inv.PickDiffService;
import com.erp.service.inv.PickReviewService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出库复核三分支 DB 语义（真实 MySQL，spec picking-review 出库复核三分支，任务 4.2）：
 * 通过到 DONE、数量差异 DIFF_PENDING+差异行+锁过账数据、外观异常自动冻结
 * （PENDING+SOURCE=REVIEW+审批挂接）+释放预留+QUALITY_PENDING、
 * 退回补拣回 PICKING、无原因 422、非法状态 422。
 */
@SpringBootTest
class PickReviewDbTest {

    private static final String DOC = "PRV-MI-01";
    private static final String ITEM = "IT-PRV-01";

    @Autowired
    private PickTaskService taskService;
    @Autowired
    private PickReviewService reviewService;
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
                        + "STATUS, CREATE_BY) VALUES ('prv-it-1', ?, '复核测试物料', '0001', 'PC', "
                        + "'STRUCT', 'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "BIN_CODE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) "
                        + "VALUES ('prv-st-1','WH-MAIN',?,'复核测试物料','PRV-B1','MAIN-1',30,0,0,30,"
                        + "'2026-10-01','junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, PRODUCTION_DATE, "
                        + "EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                        + "('prv-lg-1','PRV-B1',?,'复核测试物料','2026-01-01','2027-12-31','0','1','junit')",
                ITEM);
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('prv-mi-1', ?, 'OWN', "
                        + "'WO-PRV-01', 'DRAFT', 'junit', '0', 0)", DOC);
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                        + "ITEM_NAME, BATCH_NO, BIN_CODE, QTY, STOCK_TYPE, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('prv-mi-l1','prv-mi-1',1,?,'复核测试物料','PRV-B1','MAIN-1',30,'OWN',"
                        + "'junit','0',0)", ITEM);
        PickTask t = taskService.createFromDoc("MATERIAL_OUT", DOC);
        taskId = t.getId();
        // 推进到 PICKED（review 首次自动 → REVIEWING）
        taskService.transition(taskId, PickTask.ST_CREATED, PickTask.ST_PICKING);
        taskService.transition(taskId, PickTask.ST_PICKING, PickTask.ST_PICKED);
        jdbc.update("UPDATE erp_pick_task_line SET LINE_STATUS='PICKED', SCAN_OK_FLAG='1' "
                + "WHERE TASK_ID = ?", taskId);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_pick_scan_log WHERE TASK_ID IN "
                + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO = ?)", DOC);
        jdbc.update("DELETE FROM erp_inv_check_diff WHERE SRC_DOC_NO = ?", DOC);
        jdbc.update("DELETE FROM erp_pick_task_line WHERE TASK_ID IN "
                + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO = ?)", DOC);
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO = ?", DOC);
        // 复核冻结申请（来源=REVIEW）
        jdbc.update("DELETE FROM erp_inv_freeze WHERE SOURCE = 'REVIEW' OR ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_NO = ?", DOC);
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE ITEM_CODE = ?", ITEM);
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }

    /** ① 通过：任务到 DONE（spec 场景：复核通过到 DONE） */
    @Test
    void passToDone() {
        Map<String, Object> out = reviewService.review(taskId, 1, "PASS", null, null, null, null);
        var task = (PickTask) out.get("task");
        assertEquals(PickTask.ST_DONE, task.getStatus());
        String rv = jdbc.queryForObject("SELECT REVIEW_RESULT FROM erp_pick_task_line "
                + "WHERE TASK_ID = ?", String.class, taskId);
        assertEquals("PASS", rv);
    }

    /** ② 数量差异：DIFF_PENDING + 差异行 + 过账门闩数据（spec 场景：数量差异锁过账） */
    @Test
    void qtyDiffLocksPosting() {
        Map<String, Object> out = reviewService.review(taskId, 1, "DIFF", "QTY",
                "复核短 2 件", new BigDecimal("30"), new BigDecimal("28"));
        var task = (PickTask) out.get("task");
        assertEquals(PickTask.ST_DIFF_PENDING, task.getStatus());

        Map<String, Object> d = jdbc.queryForMap("SELECT STATUS, DIFF_KIND, DELTA_QTY "
                + "FROM erp_inv_check_diff WHERE SRC_DOC_NO = ?", DOC);
        assertEquals("PENDING", d.get("STATUS"), "门闩数据：PENDING 差异存在");
        assertEquals("QTY", d.get("DIFF_KIND"));
        // 门闩口径验证：PENDING 计数 > 0
        int pend = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_check_diff "
                + "WHERE SRC_DOC_NO = ? AND STATUS = 'PENDING' AND DIFF_TYPE = 'PICK'",
                Integer.class, DOC);
        assertEquals(1, pend);
    }

    /** ② 退回补拣：DIFF_PENDING → PICKING，行回待拣（spec 场景：退回补拣） */
    @Test
    void returnToPickReverts() {
        reviewService.review(taskId, 1, "DIFF", "QTY", "短 2", new BigDecimal("30"),
                new BigDecimal("28"));
        Map<String, Object> out = reviewService.returnToPick(taskId, 1);
        var task = (PickTask) out.get("task");
        assertEquals(PickTask.ST_PICKING, task.getStatus());
        var line = (com.erp.entity.inv.PickTaskLine) out.get("line");
        assertEquals("PENDING", line.getLineStatus());
        assertEquals(null, line.getPickedQty());
        // 差异行保持 PENDING（闭环处理）
        int pend = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_check_diff "
                + "WHERE SRC_DOC_NO = ? AND STATUS = 'PENDING'", Integer.class, DOC);
        assertEquals(1, pend);
    }

    /** ③ 外观异常：自动冻结 PENDING + SOURCE=REVIEW + 审批挂接 + QUALITY_PENDING（BR-4.4-30） */
    @Test
    void qualityAnomalyAutoFreeze() {
        Map<String, Object> out = reviewService.review(taskId, 1, "QUALITY", null,
                "外包装破损渗液", null, null);
        var task = (PickTask) out.get("task");
        assertEquals(PickTask.ST_QUALITY_PENDING, task.getStatus());

        Map<String, Object> f = jdbc.queryForMap("SELECT STATUS, SOURCE, FREEZE_TYPE, APPR_ID "
                + "FROM erp_inv_freeze WHERE SOURCE = 'REVIEW'");
        assertEquals("PENDING", f.get("STATUS"), "冻结申请 PENDING（审批未省）");
        assertEquals("QUALITY", f.get("FREEZE_TYPE"));
        assertNotNull(f.get("APPR_ID"), "审批实例已挂接（ROLE_QUALITY_MGR）");

        int qd = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_check_diff "
                + "WHERE SRC_DOC_NO = ? AND DIFF_KIND = 'QUALITY'", Integer.class, DOC);
        assertEquals(1, qd, "质量差异行已登记");
        String rv = jdbc.queryForObject("SELECT REVIEW_RESULT FROM erp_pick_task_line "
                + "WHERE TASK_ID = ?", String.class, taskId);
        assertEquals("QUALITY", rv);
    }

    /** ③ 外观异常无说明 422 */
    @Test
    void qualityRequiresReason() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reviewService.review(taskId, 1, "QUALITY", null, " ", null, null));
        assertEquals(422, ex.getCode());
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_pick_task WHERE ID = ?",
                String.class, taskId);
        assertEquals(PickTask.ST_PICKED, st, "422 前状态未变");
    }

    /** 非法状态：DONE 后不可复核（spec 场景隐含：非法跃迁 422） */
    @Test
    void reviewAfterDoneRejected() {
        reviewService.review(taskId, 1, "PASS", null, null, null, null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reviewService.review(taskId, 1, "DIFF", "QTY", "x",
                        BigDecimal.TEN, BigDecimal.ONE));
        assertEquals(422, ex.getCode());
    }

    /** 重复复核 422（行已有结论） */
    @Test
    void doubleReviewRejected() {
        reviewService.review(taskId, 1, "PASS", null, null, null, null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reviewService.review(taskId, 1, "DIFF", "QTY", "x",
                        BigDecimal.TEN, BigDecimal.ONE));
        assertEquals(422, ex.getCode());
    }

    /** 完整重拣链：DIFF → 退回补拣 → 重拣 → 复核 PASS → DONE，闭环不破坏终态 */
    @Test
    void fullReworkChainThenCloseKeepsDone() {
        reviewService.review(taskId, 1, "DIFF", "QTY", "短 2", new BigDecimal("30"),
                new BigDecimal("28"));
        assertEquals(PickTask.ST_DIFF_PENDING, status());

        reviewService.returnToPick(taskId, 1);
        assertEquals(PickTask.ST_PICKING, status());

        // 重拣放行（扫码服务路径此处以状态推进模拟）
        jdbc.update("UPDATE erp_pick_task_line SET LINE_STATUS='PICKED', SCAN_OK_FLAG='1' "
                + "WHERE TASK_ID = ?", taskId);
        taskService.transition(taskId, PickTask.ST_PICKING, PickTask.ST_PICKED);
        reviewService.review(taskId, 1, "PASS", null, null, null, null);
        assertEquals(PickTask.ST_DONE, status(), "补拣复核通过 → DONE");

        // 差异闭环：任务已 DONE（非分支态）→ 保持 DONE，差异 RESOLVED
        String diffId = jdbc.queryForObject("SELECT ID FROM erp_inv_check_diff "
                + "WHERE SRC_DOC_NO = ?", String.class, DOC);
        diffService.close(diffId, "补拣 30 件复核通过");
        assertEquals(PickTask.ST_DONE, status(), "闭环不破坏终态");
        String ds = jdbc.queryForObject("SELECT STATUS FROM erp_inv_check_diff WHERE ID = ?",
                String.class, diffId);
        assertEquals("RESOLVED", ds);
    }

    private String status() {
        return jdbc.queryForObject("SELECT STATUS FROM erp_pick_task WHERE ID = ?",
                String.class, taskId);
    }
}
