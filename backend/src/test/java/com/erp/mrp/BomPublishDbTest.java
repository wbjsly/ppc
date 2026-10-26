package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
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
 * BOM 审批发布链路（change add-bom-management，spec bom-management FR-4.5-1-6，任务 5.1/5.2）：
 * 提交生成待办 / 重复提交 422 / 主管通过回调发布+留痕 / 驳回回草稿+意见回填 /
 * 回调异常整体回滚（签署与业务一并回滚，ApprovalEngine 契约）。
 */
@SpringBootTest
class BomPublishDbTest {

    @Autowired
    private BomService bomService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem parent;
    private MdmItem child;

    @BeforeEach
    void seed() {
        cleanup();
        BomTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
        parent = mkItem("BOM发布测试父项应龙甲");
        child = mkItem("BOM发布测试子项鲲鹏乙");
    }

    @AfterEach
    void tearDown() {
        BomTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        // 审批底座残留（BomPublish 业务），先节点后实例
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID = a.ID " +
                "WHERE a.BIZ_TYPE = 'BomPublish'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'BomPublish'");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM发布测试%' " +
                "OR ITEM_NAME LIKE 'BOM权限测试%')");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE NOT IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item)");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom)");
        jdbc.update("DELETE FROM erp_mrp_bom_substitute WHERE BOM_ITEM_ID NOT IN " +
                "(SELECT ID FROM erp_mrp_bom_item)");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM发布测试%' OR ITEM_NAME LIKE 'BOM权限测试%'");
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
        item.setPackingSpec("盒");
        item.setDupNote("BOM 模块测试物料，非业务重复数据");
        return itemService.create(item, true);
    }

    private MrpBom createDraft() {
        MrpBom head = new MrpBom();
        head.setParentItemCode(parent.getItemCode());
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        return bomService.create(head, List.of(row));
    }

    private String statusOf(String bomId) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_mrp_bom WHERE ID = ?", String.class, bomId);
    }

    private String activeTaskId(String bomId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID = a.ID " +
                        "WHERE a.BIZ_TYPE = 'BomPublish' AND a.BIZ_ID = ? AND t.STATUS = 'ACTIVE'",
                String.class, bomId);
    }

    // ---------- 5.2 提交 ----------

    @Test
    void submitMovesToPendingWithApprovalInstance() {
        MrpBom draft = createDraft();
        bomService.submit(draft.getId());

        assertEquals(MrpBom.ST_PENDING, statusOf(draft.getId()), "提交后待审核");
        assertNotNull(activeTaskId(draft.getId()), "生成可签节点（待办）");

        // 主管视角待办可见（ROLE_PROCESS_MGR 可签节点）
        BomTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        List<Map<String, Object>> todos = approvalEngine.todo();
        assertTrue(todos.stream().anyMatch(t ->
                        BomService.BIZ_PUBLISH.equals(t.get("bizType"))
                                && draft.getId().equals(t.get("bizId"))
                                && "ROLE_PROCESS_MGR".equals(t.get("roleRequired"))),
                "主管待办含本单且节点角色为工艺主管");
    }

    @Test
    void duplicateSubmitRejectedWith422() {
        MrpBom draft = createDraft();
        bomService.submit(draft.getId());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> bomService.submit(draft.getId()));
        assertEquals(422, ex.getCode(), "重复提交 422");
    }

    // ---------- 5.1 回调：通过 / 驳回 / 回滚 ----------

    @Test
    void approveViaCallbackPublishesWithTrace() {
        MrpBom draft = createDraft();
        bomService.submit(draft.getId());
        String taskId = activeTaskId(draft.getId());

        BomTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意发布");

        assertEquals(MrpBom.ST_PUBLISHED, statusOf(draft.getId()), "回调同事务发布");
        assertNotNull(jdbc.queryForObject("SELECT PUBLISH_BY FROM erp_mrp_bom WHERE ID = ?",
                String.class, draft.getId()), "发布人留痕（回调执行）");
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE BIZ_TYPE='BomPublish' AND BIZ_ID = ?",
                String.class, draft.getId()), "审批实例办结");
    }

    @Test
    void rejectViaCallbackBackToDraftWithOpinion() {
        MrpBom draft = createDraft();
        bomService.submit(draft.getId());
        String taskId = activeTaskId(draft.getId());

        BomTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        approvalEngine.reject(taskId, "损耗率需要工艺复核");

        assertEquals(MrpBom.ST_DRAFT, statusOf(draft.getId()), "驳回回草稿");
        assertEquals("损耗率需要工艺复核", jdbc.queryForObject(
                "SELECT REJECT_REASON FROM erp_mrp_bom WHERE ID = ?", String.class, draft.getId()),
                "驳回意见回填版本记录");
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE BIZ_TYPE='BomPublish' AND BIZ_ID = ?",
                String.class, draft.getId()), "审批实例 REJECTED");
    }

    @Test
    void callbackExceptionRollsBackSigning() {
        MrpBom draft = createDraft();
        bomService.submit(draft.getId());
        String taskId = activeTaskId(draft.getId());
        // 破坏回调前置条件：版本被改为已废止（发布回调将 422）
        jdbc.update("UPDATE erp_mrp_bom SET STATUS = 'OBSOLETE' WHERE ID = ?", draft.getId());

        BomTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> approvalEngine.pass(taskId, "同意"));
        assertEquals(422, ex.getCode(), "回调 422 → 整体回滚");

        // 签署与审批实例一并回滚：节点仍 ACTIVE、实例仍 PENDING
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval_task WHERE ID = ?", String.class, taskId),
                "签署被回滚");
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_sys_approval WHERE BIZ_TYPE='BomPublish' AND BIZ_ID = ?",
                String.class, draft.getId()), "实例仍 PENDING");
        assertEquals(MrpBom.ST_OBSOLETE, statusOf(draft.getId()), "业务侧状态保持（回滚不覆盖手工改态）");
    }
}
