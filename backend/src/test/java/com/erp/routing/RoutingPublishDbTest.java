package com.erp.routing;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线审批发布链路（change add-routing-management，spec routing-management
 * 「审核发布复用审批底座」，任务 5.1/5.2）：
 * 提交生成待办 / 重复提交 422 / 主管通过回调发布+留痕 / 驳回回草稿+意见回填 /
 * 回调异常整体回滚。
 */
@SpringBootTest
class RoutingPublishDbTest {

    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private String opCode;
    private String wcCode;

    @BeforeEach
    void seed() {
        cleanup();
        RoutingTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
        product = mkItem("工艺测试发布品壬癸");
        opCode = "RTOP-P01";
        wcCode = "RTWC-P01";
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("发布测试工序");
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("发布测试中心");
        workCenterService.create(wc);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);
    }

    @AfterEach
    void tearDown() {
        RoutingTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        // 审批底座残留（RoutingPublish 业务），先节点后实例
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID = a.ID " +
                "WHERE a.BIZ_TYPE = 'RoutingPublish'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'RoutingPublish'");
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID IN (" +
                "SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工艺测试%'))");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工艺测试%')");
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID NOT IN " +
                "(SELECT ID FROM erp_mrp_routing)");
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE LIKE 'RTOP%' OR WC_CODE LIKE 'RTWC%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'RTOP%'");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'RTWC%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工艺测试%'");
    }

    private MdmItem mkItem(String name) {
        MdmItem item = new MdmItem();
        item.setItemName(name);
        item.setCategoryCode(categoryCode);
        item.setBaseUnit("PC");
        item.setMaterialGroup("STRUCT");
        item.setPurchaseType("BUY");
        item.setStorageCondition("NORMAL");
        item.setBatchFlag("0");
        item.setPackingSpec("箱");
        item.setDupNote("工艺路线模块测试物料，非业务重复数据");
        return itemService.create(item, true);
    }

    private MrpRouting createDraft() {
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        row.setLeadTime(BigDecimal.ONE);
        return routingService.create(head, List.of(row));
    }

    private String statusOf(String routingId) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_mrp_routing WHERE ID = ?", String.class, routingId);
    }

    private String activeTaskId(String routingId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID = a.ID " +
                        "WHERE a.BIZ_TYPE = 'RoutingPublish' AND a.BIZ_ID = ? AND t.STATUS = 'ACTIVE'",
                String.class, routingId);
    }

    // ---------- 5.2 提交 ----------

    @Test
    void submitMovesToPendingWithApprovalInstance() {
        MrpRouting draft = createDraft();
        routingService.submit(draft.getId());

        assertEquals(MrpRouting.ST_PENDING, statusOf(draft.getId()), "提交后待审核");
        assertNotNull(activeTaskId(draft.getId()), "生成可签节点（待办）");

        RoutingTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        List<Map<String, Object>> todos = approvalEngine.todo();
        assertTrue(todos.stream().anyMatch(t ->
                        RoutingService.BIZ_PUBLISH.equals(t.get("bizType"))
                                && draft.getId().equals(t.get("bizId"))
                                && "ROLE_PROCESS_MGR".equals(t.get("roleRequired"))),
                "主管待办含本单且节点角色为工艺主管");
    }

    @Test
    void duplicateSubmitRejectedWith422() {
        MrpRouting draft = createDraft();
        routingService.submit(draft.getId());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> routingService.submit(draft.getId()));
        assertEquals(422, ex.getCode(), "重复提交 422");
    }

    // ---------- 5.1 回调：通过 / 驳回 / 回滚 ----------

    @Test
    void approveViaCallbackPublishesWithTrace() {
        MrpRouting draft = createDraft();
        routingService.submit(draft.getId());
        String taskId = activeTaskId(draft.getId());

        RoutingTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意发布");

        assertEquals(MrpRouting.ST_PUBLISHED, statusOf(draft.getId()), "回调同事务发布");
        assertNotNull(jdbc.queryForObject("SELECT PUBLISH_BY FROM erp_mrp_routing WHERE ID = ?",
                String.class, draft.getId()), "发布人留痕（回调执行）");
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE BIZ_TYPE='RoutingPublish' AND BIZ_ID = ?",
                String.class, draft.getId()), "审批实例办结");
    }

    @Test
    void rejectViaCallbackBackToDraftWithOpinion() {
        MrpRouting draft = createDraft();
        routingService.submit(draft.getId());
        String taskId = activeTaskId(draft.getId());

        RoutingTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        approvalEngine.reject(taskId, "工序顺序需要复核");

        assertEquals(MrpRouting.ST_DRAFT, statusOf(draft.getId()), "驳回回草稿");
        assertEquals("工序顺序需要复核", jdbc.queryForObject(
                "SELECT REJECT_REASON FROM erp_mrp_routing WHERE ID = ?", String.class, draft.getId()),
                "驳回意见回填版本记录");
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE BIZ_TYPE='RoutingPublish' AND BIZ_ID = ?",
                String.class, draft.getId()), "审批实例 REJECTED");
    }

    @Test
    void callbackExceptionRollsBackSigning() {
        MrpRouting draft = createDraft();
        routingService.submit(draft.getId());
        String taskId = activeTaskId(draft.getId());
        // 破坏回调前置条件：版本被改为已废止（发布回调将 422）
        jdbc.update("UPDATE erp_mrp_routing SET STATUS = 'OBSOLETE' WHERE ID = ?", draft.getId());

        RoutingTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> approvalEngine.pass(taskId, "同意"));
        assertEquals(422, ex.getCode(), "回调 422 → 整体回滚");

        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval_task WHERE ID = ?", String.class, taskId),
                "签署被回滚");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE BIZ_TYPE='RoutingPublish' AND BIZ_ID = ?",
                String.class, draft.getId()), "实例仍 PENDING");
        assertEquals(MrpRouting.ST_OBSOLETE, statusOf(draft.getId()), "业务侧状态保持（回滚不覆盖手工改态）");
    }
}
