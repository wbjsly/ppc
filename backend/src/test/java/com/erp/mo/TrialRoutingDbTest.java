package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试产版 BOM/路线状态机库测（change add-trial-production-management，
 * spec bom-management / routing-management delta，任务 3.1）。
 * 试产版审批入 TRIAL、试产池与量产池并存分池、新试产版取代旧 TRIAL、TRIAL 态不可直接发布。
 */
@SpringBootTest
class TrialRoutingDbTest {

    @Autowired
    private BomService bomService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private String opCode;
    private String wcCode;
    private final List<String> touchedProducts = new ArrayList<>();

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("trial-rout-tester", "ROLE_PROCESS_ENG", "ROLE_ADMIN");

        int n = (int) (System.nanoTime() % 100000);
        opCode = "TROPP-" + n;
        wcCode = "TRWCC-" + n;
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("试产状态机工序");
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("试产状态机中心");
        workCenterService.create(wc);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN ('TrialBom','TrialRoute'))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('TrialBom','TrialRoute')");
        for (String code : touchedProducts) {
            jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID IN "
                    + "(SELECT ID FROM erp_mrp_bom WHERE PARENT_ITEM_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE=?", code);
            jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID IN "
                    + "(SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE=?", code);
        }
        touchedProducts.clear();
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE=?", opCode);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE=?", wcCode);
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE=?", opCode);
        MoSeed.cleanup(jdbc);
    }

    // ---------- 助手 ----------

    private MdmItem mkMake(String name) {
        MdmItem p = MoSeed.mkItem(itemService, categoryCode, name, "MAKE");
        touchedProducts.add(p.getItemCode());
        return p;
    }

    private MrpBom mkBomDraft(MdmItem product, MdmItem child, String stage) {
        MrpBom head = new MrpBom();
        head.setParentItemCode(product.getItemCode());
        head.setTrialStage(stage);
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        return bomService.create(head, List.of(row));
    }

    private MrpRouting mkRouteDraft(MdmItem product, String stage) {
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        row.setLeadTime(BigDecimal.ONE);
        row.setStandardYield(new BigDecimal("98"));
        row.setKeyOpFlag("0");
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        head.setTrialStage(stage);
        return routingService.create(head, List.of(row));
    }

    private String activeTaskId(String bizType, String bizId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE=? AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, bizType, bizId);
    }

    private String bomStatus(String bomId) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_mrp_bom WHERE ID=?", String.class, bomId);
    }

    private String routeStatus(String routingId) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_mrp_routing WHERE ID=?",
                String.class, routingId);
    }

    // ================= BOM 侧 =================

    @Test
    void trialBomApprovalEntersTrialState() {
        MdmItem product = mkMake("试产BOM审批产品");
        MdmItem child = mkMake("试产BOM审批子件");
        MrpBom draft = mkBomDraft(product, child, "EVT");
        assertEquals("DRAFT", bomStatus(draft.getId()), "建档为 DRAFT");
        assertEquals("EVT", jdbc.queryForObject("SELECT TRIAL_STAGE FROM erp_mrp_bom WHERE ID=?",
                String.class, draft.getId()), "TRIAL_STAGE 落库");

        // 非试产版草稿不可提交试产特别审批（独立物料对防环）
        MdmItem plainParent = mkMake("非试产草稿父件");
        MdmItem plainChild = mkMake("非试产草稿子件");
        MrpBom plain = new MrpBom();
        plain.setParentItemCode(plainParent.getItemCode());
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(plainChild.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        MrpBom plainDraft = bomService.create(plain, List.of(row));
        var notTrial = assertThrows(ServiceException.class,
                () -> bomService.submitTrial(plainDraft.getId()));
        assertEquals(422, notTrial.getCode(), "非试产版 422：" + notTrial.getMessage());
        assertTrue(notTrial.getMessage().contains("未标记试产阶段"), notTrial.getMessage());

        // 提交 → 工艺主管通过 → TRIAL 态
        bomService.submitTrial(draft.getId());
        String taskId = activeTaskId("TrialBom", draft.getId());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意试产版");
        MoTestAuth.login("trial-rout-tester", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        assertEquals("TRIAL", bomStatus(draft.getId()), "审批通过 DRAFT → TRIAL（delta 场景）");

        // TRIAL 态提交正式发布 → 422（须经转量产复制）
        var directPublish = assertThrows(ServiceException.class,
                () -> bomService.submit(draft.getId()));
        assertEquals(422, directPublish.getCode(), "TRIAL 直接发布 422：" + directPublish.getMessage());
        assertTrue(directPublish.getMessage().contains("转量产"), directPublish.getMessage());
    }

    @Test
    void trialBomPoolCoexistsAndSupersede() {
        MdmItem product = mkMake("试产池并存产品");
        MdmItem child = mkMake("试产池并存子件");

        // 量产已发布版本
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");

        // 试产池与量产池并存：量产在途草稿不挡试产创建
        MrpBom massDraft = new MrpBom();
        massDraft.setParentItemCode(product.getItemCode());
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        MrpBom mass = bomService.create(massDraft, List.of(row)); // 量产在途
        MrpBom trial1 = mkBomDraft(product, child, "DVT");
        assertEquals("DRAFT", bomStatus(trial1.getId()), "量产在途时试产草稿放行（池隔离）");

        // 试产池内重复在途阻断
        var dupTrial = assertThrows(ServiceException.class,
                () -> mkBomDraft(product, child, "PVT"));
        assertEquals(422, dupTrial.getCode(), "试产池内重复在途 422：" + dupTrial.getMessage());
        assertTrue(dupTrial.getMessage().contains("试产池"), dupTrial.getMessage());

        // 量产池内重复在途阻断（原有语义回归）
        var dupMass = assertThrows(ServiceException.class,
                () -> bomService.create(massDraft, List.of(row)));
        assertEquals(422, dupMass.getCode(), "量产池在途阻断回归");
        assertTrue(!dupMass.getMessage().contains("试产池"), "量产池消息不含试产池：" + dupMass.getMessage());

        // trial1 审批通过 → TRIAL（量产在途仍共存）
        bomService.submitTrial(trial1.getId());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(activeTaskId("TrialBom", trial1.getId()), "同意");
        MoTestAuth.login("trial-rout-tester", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        assertEquals("TRIAL", bomStatus(trial1.getId()));
        assertEquals("DRAFT", bomStatus(mass.getId()), "试产入 TRIAL 不影响量产在途草稿");

        // 旧 TRIAL 已占位 → 新试产版（走变更克隆 TRIAL_STAGE 继承）审批通过时旧 TRIAL→REVISED
        MrpBom next = bomService.change(trial1.getId(), "DVT 迭代", false);
        assertEquals("DRAFT", bomStatus(next.getId()), "试产版变更克隆为草稿");
        assertEquals("DVT", jdbc.queryForObject("SELECT TRIAL_STAGE FROM erp_mrp_bom WHERE ID=?",
                String.class, next.getId()), "TRIAL_STAGE 继承");
        bomService.submitTrial(next.getId());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(activeTaskId("TrialBom", next.getId()), "同意新版");
        MoTestAuth.login("trial-rout-tester", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        assertEquals("TRIAL", bomStatus(next.getId()), "新试产版入 TRIAL");
        assertEquals("REVISED", bomStatus(trial1.getId()), "旧 TRIAL 自动迁移 REVISED（试产唯一已审批）");
        assertEquals("DRAFT", bomStatus(mass.getId()), "量产在途草稿全程不受影响");
    }

    @Test
    void trialBomRejectStaysDraftWithOpinion() {
        MdmItem product = mkMake("试产BOM驳回产品");
        MdmItem child = mkMake("试产BOM驳回子件");
        MrpBom draft = mkBomDraft(product, child, "EVT");
        bomService.submitTrial(draft.getId());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.reject(activeTaskId("TrialBom", draft.getId()), "参数验证不足");
        MoTestAuth.login("trial-rout-tester", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        assertEquals("DRAFT", bomStatus(draft.getId()), "驳回状态保持 DRAFT");
        assertEquals("参数验证不足", jdbc.queryForObject(
                "SELECT REJECT_REASON FROM erp_mrp_bom WHERE ID=?", String.class, draft.getId()),
                "驳回意见留痕");
    }

    // ================= 路线侧 =================

    @Test
    void trialRouteApprovalAndPool() {
        MdmItem product = mkMake("试产路线状态机产品");

        // 量产路线发布（池隔离对照）
        MoSeed.publishRouting(routingService, operationService, workCenterService,
                standardService, jdbc, product, 2);

        // 试产路线草稿 → 审批 → TRIAL
        MrpRouting trial = mkRouteDraft(product, "PVT");
        assertEquals("DRAFT", routeStatus(trial.getId()));
        assertEquals("PVT", jdbc.queryForObject(
                "SELECT TRIAL_STAGE FROM erp_mrp_routing WHERE ID=?", String.class, trial.getId()),
                "路线 TRIAL_STAGE 落库");

        routingService.submitTrial(trial.getId());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(activeTaskId("TrialRoute", trial.getId()), "同意试产路线");
        MoTestAuth.login("trial-rout-tester", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        assertEquals("TRIAL", routeStatus(trial.getId()), "路线 DRAFT → TRIAL（delta 场景）");

        // TRIAL 态直接提交正式发布 → 422
        var directPublish = assertThrows(ServiceException.class,
                () -> routingService.submit(trial.getId()));
        assertEquals(422, directPublish.getCode(), "路线 TRIAL 直接发布 422：" + directPublish.getMessage());
        assertTrue(directPublish.getMessage().contains("转量产"), directPublish.getMessage());

        // 试产池内重复在途阻断（trial 已 TRIAL，新草稿可建；再建第三个被在途挡）
        MrpRouting next = mkRouteDraft(product, "PVT");
        var dup = assertThrows(ServiceException.class, () -> mkRouteDraft(product, "EVT"));
        assertEquals(422, dup.getCode(), "路线试产池重复在途 422：" + dup.getMessage());
        assertTrue(dup.getMessage().contains("试产"), dup.getMessage());

        // 新试产版审批 → 旧 TRIAL→REVISED（试产唯一已审批）
        routingService.submitTrial(next.getId());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(activeTaskId("TrialRoute", next.getId()), "同意新版");
        MoTestAuth.login("trial-rout-tester", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        assertEquals("TRIAL", routeStatus(next.getId()));
        assertEquals("REVISED", routeStatus(trial.getId()), "旧路线 TRIAL → REVISED");
    }

    @Test
    void trialRouteRejectStaysDraft() {
        MdmItem product = mkMake("试产路线驳回产品");
        MrpRouting draft = mkRouteDraft(product, "EVT");
        routingService.submitTrial(draft.getId());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.reject(activeTaskId("TrialRoute", draft.getId()), "工序行不完整");
        MoTestAuth.login("trial-rout-tester", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        assertEquals("DRAFT", routeStatus(draft.getId()), "驳回保持 DRAFT");
        assertEquals("工序行不完整", jdbc.queryForObject(
                "SELECT REJECT_REASON FROM erp_mrp_routing WHERE ID=?", String.class, draft.getId()),
                "驳回意见留痕");
        assertNotNull(draft.getId());
    }
}
