package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.PickTask;
import com.erp.service.inv.PickTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拣货任务 DB 语义（真实 MySQL，spec picking-review 拣货任务生成与生命周期，任务 2.1）：
 * 生成幂等（重复确认同步不重建）、作废后重建、改派留痕、作废原因必填、
 * 状态机非法迁移 422、过账联动 COMPLETED、非授权 401/403。
 */
@SpringBootTest
class PickTaskDbTest {

    private static final String DOC = "PT-DB-MI-01";

    @Autowired
    private PickTaskService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        purge();
        asWarehouse();
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                        + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                        + "STATUS, CREATE_BY) VALUES ('pt-it-1','IT-PT-01','拣货任务物料','0001','PC',"
                        + "'STRUCT','BUY','NORMAL','1','1','junit')");
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES ('pt-mi-1', ?, 'OWN', "
                        + "'WO-PT-01', 'DRAFT', 'junit', '0', 0)", DOC);
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                + "ITEM_NAME, BATCH_NO, BIN_CODE, QTY, STOCK_TYPE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('pt-mi-l1','pt-mi-1','1','IT-PT-01','拣货任务物料','PT-B1','A-01',"
                + "30,'OWN','junit','0',0)");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pick_task_line WHERE TASK_ID IN "
                + "(SELECT ID FROM erp_pick_task WHERE SRC_DOC_NO = ?)", DOC);
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO = ?", DOC);
        jdbc.update("DELETE FROM erp_pick_task WHERE SRC_DOC_NO LIKE 'PT-DB-%'");
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ITEM_CODE = 'IT-PT-01'");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_NO LIKE 'PT-DB-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = 'IT-PT-01'");
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }

    private PickTask create() {
        return service.createFromDoc("MATERIAL_OUT", DOC);
    }

    /** 生成幂等：重复调用同步行不重建（spec 场景：重复确认不重复生成） */
    @Test
    void createIsIdempotentAndSyncsLines() {
        PickTask t1 = create();
        assertNotNull(t1.getTaskNo());
        assertEquals(PickTask.ST_CREATED, t1.getStatus());

        PickTask t2 = create();
        assertEquals(t1.getId(), t2.getId(), "重复生成返回同一任务");
        int taskCnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_pick_task "
                + "WHERE SRC_DOC_NO = ?", Integer.class, DOC);
        assertEquals(1, taskCnt);

        // 回写值变更 → 同步行
        jdbc.update("UPDATE erp_inv_material_issue_line SET BATCH_NO='PT-B2', BIN_CODE='B-09' "
                + "WHERE ISSUE_ID='pt-mi-1'");
        create();
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT BATCH_NO, BIN_CODE FROM erp_pick_task_line WHERE TASK_ID = ?", t1.getId());
        assertEquals("PT-B2", line.get("BATCH_NO"));
        assertEquals("B-09", line.get("BIN_CODE"));
    }

    /** 作废后重新确认 → 生成新任务（spec 场景：作废任务） */
    @Test
    void recreateAfterCancel() {
        PickTask t1 = create();
        service.cancel(t1.getId(), "工单取消");
        assertEquals(PickTask.ST_CANCELLED, service.detail(t1.getId())
                .containsKey("task") ? ((PickTask) service.detail(t1.getId()).get("task")).getStatus()
                : "?");

        PickTask t2 = create();
        assertNotEquals(t1.getId(), t2.getId(), "作废后重建为新任务");
        assertEquals(PickTask.ST_CREATED, t2.getStatus());
    }

    /** 作废原因必填 422 */
    @Test
    void cancelRequiresReason() {
        PickTask t = create();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.cancel(t.getId(), " "));
        assertEquals(422, ex.getCode());
        assertEquals(PickTask.ST_CREATED,
                ((PickTask) service.detail(t.getId()).get("task")).getStatus());
    }

    /** 改派留痕前后人员（spec 场景：改派留痕） */
    @Test
    void assignTrailsPreviousPicker() {
        PickTask t = create();
        service.assign(t.getId(), "picker-A");
        Map<String, Object> d = service.detail(t.getId());
        PickTask after = (PickTask) d.get("task");
        assertEquals("picker-A", after.getPicker());
        assertEquals("tester", after.getAssignBy(), "操作人留痕");

        service.assign(t.getId(), "picker-B");
        PickTask after2 = (PickTask) service.detail(t.getId()).get("task");
        assertEquals("picker-B", after2.getPicker());
        assertEquals("picker-A", after2.getPrevPicker(), "改派前人员留痕");
    }

    /** 状态机：非法迁移 422（spec 场景：非法状态跃迁拒绝） */
    @Test
    void illegalTransitionRejected() {
        PickTask t = create();
        // CREATED 不能直接 DONE
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition(t.getId(), PickTask.ST_CREATED, PickTask.ST_DONE));
        assertEquals(422, ex.getCode());
        // CREATED → PICKING → PICKED 合法
        service.transition(t.getId(), PickTask.ST_CREATED, PickTask.ST_PICKING);
        PickTask p = (PickTask) service.detail(t.getId()).get("task");
        assertEquals(PickTask.ST_PICKING, p.getStatus());
        // from 状态给错 → 422
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> service.transition(t.getId(), PickTask.ST_CREATED, PickTask.ST_PICKED));
        assertEquals(422, ex2.getCode());
    }

    /** 过账联动：DONE → COMPLETED；非 DONE 跳过（spec 场景：过账成功联动） */
    @Test
    void markCompletedOnlyFromDone() {
        PickTask t = create();
        // 非 DONE → 跳过，状态不变
        service.markCompleted("MATERIAL_OUT", DOC);
        assertEquals(PickTask.ST_CREATED,
                ((PickTask) service.detail(t.getId()).get("task")).getStatus());

        service.transition(t.getId(), PickTask.ST_CREATED, PickTask.ST_PICKING);
        service.transition(t.getId(), PickTask.ST_PICKING, PickTask.ST_PICKED);
        service.transition(t.getId(), PickTask.ST_PICKED, PickTask.ST_REVIEWING);
        service.transition(t.getId(), PickTask.ST_REVIEWING, PickTask.ST_DONE);
        service.markCompleted("MATERIAL_OUT", DOC);
        PickTask done = (PickTask) service.detail(t.getId()).get("task");
        assertEquals(PickTask.ST_COMPLETED, done.getStatus());
        assertNotNull(done.getCompleteAt(), "完成时间留痕");
    }

    /** 无任务单据：markCompleted 空操作不报错（spec 场景：无任务单据不受影响） */
    @Test
    void markCompletedWithoutTaskIsNoop() {
        service.markCompleted("MATERIAL_OUT", "NO-SUCH-DOC");   // 不抛即通过
    }

    /** 非授权 401/403（spec 场景：非授权写操作） */
    @Test
    void writePermissionEnforced() {
        PickTask t = create();
        SecurityContextHolder.clearContext();
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.assign(t.getId(), "x"));
        assertEquals(401, e1.getCode(), "未登录 401");

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("u", null, "ROLE_USER"));
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.cancel(t.getId(), "原因"));
        assertEquals(403, e2.getCode(), "非授权 403");
    }

    /** 分页筛选 */
    @Test
    void pageFilters() {
        create();
        Map<String, Object> p = service.page("CREATED", "MATERIAL_OUT", "PT-DB", 1, 10);
        assertTrue(((Number) p.get("total")).longValue() >= 1);
        Map<String, Object> miss = service.page("COMPLETED", null, "NO-SUCH", 1, 10);
        assertEquals(0L, ((Number) miss.get("total")).longValue());
    }
}
