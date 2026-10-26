package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.TrialApply;
import com.erp.entity.mrp.TrialData;
import com.erp.entity.mrp.TrialSummary;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.TrialApplyService;
import com.erp.service.mrp.TrialDataService;
import com.erp.service.mrp.TrialOrderService;
import com.erp.service.mrp.TrialSummaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 试产权限矩阵（task 4.1，spec trial-order/trial-execution「菜单与权限」+ design D12）：
 * 服务层即 spec 校验点——SHOP_FLOOR 全 403 / PLANNER 建单过门·试产版审批 403 /
 * PROCESS_ENG 采集过门·财务确认 403 / 未认证 401。
 */
@SpringBootTest
class TrialPermissionDbTest {

    @Autowired
    private TrialApplyService applyService;
    @Autowired
    private TrialOrderService orderService;
    @Autowired
    private TrialDataService dataService;
    @Autowired
    private TrialSummaryService summaryService;
    @Autowired
    private MoService moService;
    @Autowired
    private BomService bomService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private final List<String> touchedProducts = new ArrayList<>();

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('TrialCostReverse','TrialBom'))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('TrialCostReverse','TrialBom')");
        jdbc.update("DELETE FROM erp_mrp_trial_summary");
        jdbc.update("DELETE FROM erp_mrp_trial_data");
        jdbc.update("DELETE FROM erp_mrp_trial_apply");
        for (String code : touchedProducts) {
            jdbc.update("DELETE FROM erp_mrp_mo_op WHERE MO_ID IN "
                    + "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN "
                    + "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE=?", code);
            jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID IN "
                    + "(SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE=?", code);
            jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID IN "
                    + "(SELECT ID FROM erp_mrp_bom WHERE PARENT_ITEM_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE=?", code);
        }
        touchedProducts.clear();
        MoSeed.cleanup(jdbc);
    }

    private com.erp.entity.mdm.MdmItem mkMake(String name) {
        com.erp.entity.mdm.MdmItem p = MoSeed.mkItem(itemService, categoryCode, name, "MAKE");
        touchedProducts.add(p.getItemCode());
        return p;
    }

    private TrialApply applyHead(com.erp.entity.mdm.MdmItem product, String stage) {
        TrialApply h = new TrialApply();
        h.setTrialStage(stage);
        h.setProductCode(product.getItemCode());
        h.setQty(BigDecimal.TEN);
        h.setGoal("权限验证");
        return h;
    }

    /** 试产版 BOM 草稿（TRIAL_STAGE 置位） */
    private MrpBom trialBomDraft(com.erp.entity.mdm.MdmItem product) {
        com.erp.entity.mdm.MdmItem child = mkMake(product.getItemName() + "权限子件");
        MrpBom head = new MrpBom();
        head.setParentItemCode(product.getItemCode());
        head.setTrialStage("EVT");
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        return bomService.create(head, List.of(row));
    }

    @Test
    void permissionMatrix() {
        MoTestAuth.login("tperm-seed", "ROLE_PROCESS_ENG", "ROLE_PLANNER", "ROLE_ADMIN");
        com.erp.entity.mdm.MdmItem product = mkMake("试产权限产品");
        MoSeed.publishBom(bomService, jdbc, product, mkMake("试产权限子件"), "1", "0");
        MoSeed.publishRouting(routingService, operationService, workCenterService,
                standardService, jdbc, product, 1);
        MrpBom trialBom = trialBomDraft(product);
        MoTestAuth.logout();

        // ① SHOP_FLOOR：全 403（菜单不可见的接口面）
        MoTestAuth.login("tperm-floor", "ROLE_SHOP_FLOOR");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> applyService.create(applyHead(product, "EVT"))).getCode(), "SHOP_FLOOR 建申请 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> applyService.list(null, null, null)).getCode(), "SHOP_FLOOR 申请列表 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> orderService.createFromApply("TA-X", null, null, true)).getCode(),
                "SHOP_FLOOR 转开 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> orderService.list(null, null)).getCode(), "SHOP_FLOOR 工单列表 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> orderService.phaseOf("mo-x")).getCode(), "SHOP_FLOOR 阶段推导 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> dataService.save(new TrialData())).getCode(), "SHOP_FLOOR 采集 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> dataService.form("mo-x", 10)).getCode(), "SHOP_FLOOR 采集表单 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> summaryService.save(new TrialSummary())).getCode(), "SHOP_FLOOR 总结 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> summaryService.list(null, null)).getCode(), "SHOP_FLOOR 总结列表 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> orderService.promote("mo-x")).getCode(), "SHOP_FLOOR 转量产 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> orderService.costOf("mo-x")).getCode(), "SHOP_FLOOR 成本 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> orderService.remainderOf("mo-x")).getCode(), "SHOP_FLOOR 剩余料 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> orderService.ackRemainder("mo-x", null)).getCode(), "SHOP_FLOOR 剩余料确认 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> bomService.submitTrial(trialBom.getId())).getCode(),
                "SHOP_FLOOR 试产版审批提交 403");

        // ② PLANNER：建单过门（走到业务校验 422）、试产版审批 403（工艺面）
        MoTestAuth.login("tperm-planner", "ROLE_PLANNER");
        TrialApply ok = applyService.create(applyHead(product, "EVT"));
        assertNotNull(ok.getTrialNo(), "PLANNER 建申请过门（TA- 编号）");
        assertEquals(422, assertThrows(ServiceException.class,
                () -> orderService.createFromApply("TA-NO-SUCH", null, null, true)).getCode(),
                "PLANNER 转开过权限门（申请单不存在 422）");
        orderService.list(null, null); // 计划侧列表可读
        assertEquals(403, assertThrows(ServiceException.class,
                () -> bomService.submitTrial(trialBom.getId())).getCode(),
                "PLANNER 试产版审批 403（仅 PROCESS_ENG/MGR/ADMIN）");

        // ③ PROCESS_ENG：采集过门、试产版审批过门、财务确认 403
        MoTestAuth.login("tperm-eng", "ROLE_PROCESS_ENG");
        // 采集过门（走到工序/工单校验 422）
        var dataBlocked = assertThrows(ServiceException.class,
                () -> dataService.save(new TrialData()));
        assertEquals(422, dataBlocked.getCode(), "PROCESS_ENG 采集过权限门（必填 422）：" + dataBlocked.getMessage());
        assertEquals(422, assertThrows(ServiceException.class,
                () -> dataService.form("mo-no-such", 10)).getCode(),
                "PROCESS_ENG 采集表单过门（工单不存在 422）");
        // 试产版审批提交过门（PROCESS_ENG 可发起——挂 TrialBom 实例）
        MrpBom submitted = bomService.submitTrial(trialBom.getId());
        assertNotNull(submitted.getId(), "PROCESS_ENG 发起试产审批过门");
        String trialBomTask = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID"
                        + " WHERE a.BIZ_TYPE='TrialBom' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, trialBom.getId());
        // 但签署节点 = PROCESS_MGR → PROCESS_ENG 403（design D12 试产版审批签署面）
        assertEquals(403, assertThrows(ServiceException.class,
                () -> approvalEngine.pass(trialBomTask, "我来签")).getCode(),
                "PROCESS_ENG 签试产版审批 403（节点需 PROCESS_MGR）");
        // 财务确认（TrialCostReverse 节点 ROLE_FINANCE_MGR 签署）→ PROCESS_ENG 403
        ApprovalInstance inst = approvalEngine.submit("TrialCostReverse", "tc-perm-1",
                "权限验证：冲回财务确认", null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "财务主管"))));
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND STATUS='ACTIVE'",
                String.class, inst.getId());
        assertEquals(403, assertThrows(ServiceException.class,
                () -> approvalEngine.pass(taskId, "我来确认")).getCode(),
                "PROCESS_ENG 财务确认 403（节点需 FINANCE_MGR）");

        // ④ 未认证 401
        MoTestAuth.logout();
        assertEquals(401, assertThrows(ServiceException.class,
                () -> applyService.create(applyHead(product, "EVT"))).getCode(), "未认证建申请 401");
        assertEquals(401, assertThrows(ServiceException.class,
                () -> orderService.phaseOf("mo-x")).getCode(), "未认证阶段推导 401");
        assertEquals(401, assertThrows(ServiceException.class,
                () -> dataService.form("mo-x", 10)).getCode(), "未认证采集表单 401");
        assertEquals(401, assertThrows(ServiceException.class,
                () -> summaryService.list(null, null)).getCode(), "未认证总结列表 401");
    }

    // 沿用 3.x 工序/工作中心/定额服务（seed 侧造路线用）
    @Autowired
    private com.erp.service.mrp.OperationService operationService;
    @Autowired
    private com.erp.service.mrp.WorkCenterService workCenterService;
    @Autowired
    private com.erp.service.mrp.OpWcStandardService standardService;
}
