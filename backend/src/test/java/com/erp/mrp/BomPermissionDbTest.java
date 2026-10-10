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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BOM 角色权限矩阵（change add-bom-management，spec bom-management 权限控制，任务 5.3）：
 * 未认证 401 / 工程师调发布（签署）403 / 工程师废止 403 / 主管发布与废止成功 / ADMIN 未单测（直通逻辑同 requireAny）。
 */
@SpringBootTest
class BomPermissionDbTest {

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
        parent = mkItem("BOM权限测试父项青鸾甲");
        child = mkItem("BOM权限测试子项朱雀乙");
    }

    @AfterEach
    void tearDown() {
        BomTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID = a.ID " +
                "WHERE a.BIZ_TYPE = 'BomPublish'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'BomPublish'");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM权限测试%' " +
                "OR ITEM_NAME LIKE 'BOM发布测试%')");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE NOT IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item)");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom)");
        jdbc.update("DELETE FROM erp_mrp_bom_substitute WHERE BOM_ITEM_ID NOT IN " +
                "(SELECT ID FROM erp_mrp_bom_item)");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM权限测试%' OR ITEM_NAME LIKE 'BOM发布测试%'");
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

    private void promoteAndPublish(MrpBom draft) {
        jdbc.update("UPDATE erp_mrp_bom SET STATUS = 'PENDING' WHERE ID = ?", draft.getId());
        bomService.publishApproved(draft.getId());
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

    // ---------- 任务 5.3 三场景 + 工程师签署 403 ----------

    @Test
    void unauthenticatedWriteRejectedWith401() {
        BomTestAuth.logout();
        MrpBom head = new MrpBom();
        head.setParentItemCode(parent.getItemCode());
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> bomService.create(head, List.of(row)));
        assertEquals(401, ex.getCode(), "未认证 401：" + ex.getMessage());
    }

    @Test
    void engineerCannotObsolete403() {
        MrpBom draft = createDraft();
        promoteAndPublish(draft);
        // 已是 ROLE_PROCESS_ENG 登录态
        ServiceException ex = assertThrows(ServiceException.class,
                () -> bomService.obsolete(draft.getId()));
        assertEquals(403, ex.getCode(), "工程师废止 403：" + ex.getMessage());
        assertEquals(MrpBom.ST_PUBLISHED, statusOf(draft.getId()), "状态未变");
    }

    @Test
    void engineerCannotSignPublish403() {
        MrpBom draft = createDraft();
        bomService.submit(draft.getId());
        String taskId = activeTaskId(draft.getId());
        // 工程师尝试签署工艺主管节点 → 底座角色校验 403
        ServiceException ex = assertThrows(ServiceException.class,
                () -> approvalEngine.pass(taskId, "我来发布"));
        assertEquals(403, ex.getCode(), "工程师调发布签署 403");
        assertEquals(MrpBom.ST_PENDING, statusOf(draft.getId()), "状态未推进");
    }

    @Test
    void managerCanPublishAndObsolete() {
        MrpBom draft = createDraft();
        bomService.submit(draft.getId());
        String taskId = activeTaskId(draft.getId());

        BomTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意");
        assertEquals(MrpBom.ST_PUBLISHED, statusOf(draft.getId()), "主管通过即发布");

        bomService.obsolete(draft.getId());
        assertEquals(MrpBom.ST_OBSOLETE, statusOf(draft.getId()), "主管废止成功");
    }
}
