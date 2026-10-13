package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.TrialApply;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.TrialApplyService;
import com.erp.service.mrp.TrialOrderService;
import com.erp.service.mrp.WorkCenterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试产工单库测（change add-trial-production-management，trial-order 族）。
 * 2.1 载体编码/三池在途隔离；后续 2.2~2.6 申请/卡控/生命周期用例随任务追加。
 */
@SpringBootTest
class TrialOrderDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private TrialApplyService trialApplyService;
    @Autowired
    private TrialOrderService trialOrderService;
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
    private JdbcTemplate jdbc;

    private String categoryCode;
    private String opCode;
    private String wcCode;
    private final List<String> touchedProducts = new ArrayList<>();

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        // 跨源残留防护：冒烟脚本（trial-smoke.sh）留下的 TRIAL 工单与本族 TA 编号
        // （按行数计数）撞号 → SRC_NO 断言误命中；测试启动先清 TRIAL 池
        jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN " +
                "(SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL')");
        jdbc.update("DELETE FROM erp_mrp_mo_op WHERE MO_ID IN " +
                "(SELECT ID FROM erp_mrp_mo WHERE MO_TYPE='TRIAL')");
        jdbc.update("DELETE FROM erp_mrp_mo WHERE MO_TYPE='TRIAL'");
        jdbc.update("DELETE FROM erp_mrp_trial_cost"); jdbc.update("DELETE FROM erp_mrp_trial_summary");
        jdbc.update("DELETE FROM erp_mrp_trial_data"); jdbc.update("DELETE FROM erp_mrp_trial_apply");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID " +
                "WHERE a.BIZ_TYPE IN ('TrialApply','TrialBom','TrialRoute','TrialPvtDiff')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN " +
                "('TrialApply','TrialBom','TrialRoute','TrialPvtDiff')");
        // 冒烟残留领料单（TP- 编号按日回收同号 → 剩余料卡控误命中）
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID IN " +
                "(SELECT ID FROM erp_inv_material_issue WHERE WORK_ORDER_NO LIKE 'TP-%')");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE WORK_ORDER_NO LIKE 'TP-%'");
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("trial-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");

        int n = (int) (System.nanoTime() % 100000);
        opCode = "TROPP-" + n;
        wcCode = "TRWCC-" + n;
        com.erp.entity.mrp.MrpOperation op = new com.erp.entity.mrp.MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("试产测试工序");
        operationService.create(op);
        com.erp.entity.mrp.MrpWorkCenter wc = new com.erp.entity.mrp.MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("试产测试中心");
        workCenterService.create(wc);
        com.erp.entity.mrp.MrpOpWcStandard std = new com.erp.entity.mrp.MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setSetupHours(new BigDecimal("0.5"));
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('TrialApply','TrialBom','TrialRoute','TrialReview','TrialPvtDiff',"
                + "'TrialBorrow','TrialCostReverse'))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('TrialApply','TrialBom','TrialRoute','TrialReview','TrialPvtDiff',"
                + "'TrialBorrow','TrialCostReverse')");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'TRIAL_%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_ID LIKE '%#TRIAL'");
        jdbc.update("DELETE FROM erp_mrp_trial_cost");
        jdbc.update("DELETE FROM erp_mrp_trial_summary");
        jdbc.update("DELETE FROM erp_mrp_trial_data");
        jdbc.update("DELETE FROM erp_mrp_trial_apply");
        for (String code : touchedProducts) {
            jdbc.update("DELETE FROM erp_mrp_mo_op WHERE MO_ID IN "
                    + "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE MO_ID IN "
                    + "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE=?", code);
            jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID IN "
                    + "(SELECT ID FROM erp_mrp_bom WHERE PARENT_ITEM_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE=?", code);
        }
        touchedProducts.clear();
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE=?", opCode);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE=?", wcCode);
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE=?", opCode);
        MoSeed.cleanup(jdbc);
    }

    // ---------- 种子助手 ----------

    private int seq() {
        return (int) (System.nanoTime() % 1000000);
    }

    private MdmItem mkMake(String name) {
        MdmItem p = MoSeed.mkItem(itemService, categoryCode, name, "MAKE");
        touchedProducts.add(p.getItemCode());
        return p;
    }

    private MdmItem productWithBom(String name) {
        MdmItem p = mkMake(name);
        MdmItem child = mkMake(name + "子件");
        MoSeed.publishBom(bomService, jdbc, p, child, "1", "0");
        return p;
    }

    private MrpMo createNormal(MdmItem product, String qty) {
        MrpMo h = new MrpMo();
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(7));
        return (MrpMo) moService.create(h).get("mo");
    }

    /** 直插试产在途行（2.1 编号口与分池判定；模拟建单路径=先在途校验再编号插入） */
    private MrpMo insertTrial(MdmItem product, String qty, String stage) {
        moService.checkNoInFlight(product.getItemCode(), MrpMo.MO_TYPE_TRIAL, stage);
        MrpMo mo = new MrpMo();
        mo.setProductCode(product.getItemCode());
        mo.setProductName(product.getItemName());
        mo.setQty(new BigDecimal(qty));
        mo.setMoType(MrpMo.MO_TYPE_TRIAL);
        mo.setTrialStage(stage);
        mo.setStatus(MrpMo.ST_RELEASED);
        mo.setPriority(5);
        mo.setShortageFlag("0");
        mo.setPlanStartDate(LocalDate.now());
        mo.setPlanEndDate(LocalDate.now().plusDays(7));
        return moService.insertWithMoNo(mo);
    }

    // ================= 2.1 载体与编码分池 =================

    @Test
    void trialNumberingPoolAndThreePoolTransit() {
        MdmItem product = productWithBom("试产载体产品");

        // TP- 编码与 MO- 序列互不占用
        MrpMo normal = createNormal(product, "10");
        assertTrue(normal.getMoNo().startsWith("MO-"), "正常工单 MO- 前缀：" + normal.getMoNo());
        MrpMo t1 = insertTrial(product, "5", "EVT");
        assertTrue(t1.getMoNo().startsWith("TP-"), "试产工单 TP- 前缀：" + t1.getMoNo());
        MdmItem productSeq = productWithBom("试产序列产品");
        MrpMo t2 = insertTrial(productSeq, "3", "EVT");
        assertTrue(t2.getMoNo().startsWith("TP-"));
        String n1 = t1.getMoNo().substring(t1.getMoNo().length() - 3);
        String n2 = t2.getMoNo().substring(t2.getMoNo().length() - 3);
        assertEquals(Integer.parseInt(n1) + 1, Integer.parseInt(n2),
                "TP 序列独立连续：" + t1.getMoNo() + " → " + t2.getMoNo());
        assertEquals("TRIAL", jdbc.queryForObject(
                "SELECT MO_TYPE FROM erp_mrp_mo WHERE ID=?", String.class, t1.getId()),
                "MO_TYPE=TRIAL 落库");
        assertEquals("EVT", jdbc.queryForObject(
                "SELECT TRIAL_STAGE FROM erp_mrp_mo WHERE ID=?", String.class, t1.getId()),
                "TRIAL_STAGE 落库");

        // 三类型在途互不阻断（同产品 NORMAL + TRIAL 并存）
        MdmItem p2 = productWithBom("三池混存产品");
        createNormal(p2, "4");
        insertTrial(p2, "2", "EVT");

        // 试产池内同阶段在途阻断、不同阶段放行（阶段细分）
        MdmItem p3 = productWithBom("阶段细分产品");
        insertTrial(p3, "6", "EVT");
        var blocked = assertThrows(ServiceException.class,
                () -> insertTrial(p3, "1", "EVT"));
        assertEquals(422, blocked.getCode(),
                "同阶段试产在途阻断：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("试产"), "阻断消息标明试产池");
        MrpMo dvt = insertTrial(p3, "1", "DVT");
        assertTrue(dvt.getMoNo().startsWith("TP-"), "不同阶段试产并行放行");

        // NORMAL 池在途阻断不回退
        MdmItem p4 = productWithBom("正常阻断回归产品");
        createNormal(p4, "3");
        var normalBlocked = assertThrows(ServiceException.class,
                () -> createNormal(p4, "1"));
        assertEquals(422, normalBlocked.getCode(), "NORMAL 池唯一在途阻断回归");
    }

    // ================= 2.2 申请单建档与审批 =================

    private String activeApplyTaskId(String trialNo) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE='TrialApply' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, trialNo);
    }

    private TrialApply mkApply(MdmItem product, String stage, String goal, String qty) {
        TrialApply h = new TrialApply();
        h.setTrialStage(stage);
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setGoal(goal);
        return trialApplyService.create(h);
    }

    /** 建档 → 提交 → 工艺主管通过 → 返回申请单号 */
    private String approvedApply(MdmItem product, String stage, String qty, String goal) {
        TrialApply a = mkApply(product, stage, goal, qty);
        trialApplyService.submit(a.getTrialNo());
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE='TrialApply' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, a.getTrialNo());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意试产");
        MoTestAuth.login("trial-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        return a.getTrialNo();
    }

    /** 试产版 BOM：建草稿后置 TRIAL_STAGE；approve=true 再置 TRIAL 态（模拟特别审批落态） */
    private String mkTrialBom(MdmItem product, MdmItem child, String qty, String loss,
                              String stage, boolean approveTrial) {
        MrpBom head = new MrpBom();
        head.setParentItemCode(product.getItemCode());
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(new BigDecimal(qty));
        row.setLossRate(new BigDecimal(loss));
        MrpBom draft = bomService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_bom SET TRIAL_STAGE=? WHERE ID=?", stage, draft.getId());
        if (approveTrial) {
            jdbc.update("UPDATE erp_mrp_bom SET STATUS='TRIAL' WHERE ID=?", draft.getId());
        }
        return draft.getId();
    }

    /** 试产版路线（建草稿后 JDBC 置 TRIAL 态 + 阶段） */
    private String mkTrialRoute(MdmItem product, String stage) {
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        row.setLeadTime(BigDecimal.ONE);
        row.setStandardYield(new BigDecimal("98"));
        row.setKeyOpFlag("0");
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRouting draft = routingService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='TRIAL', TRIAL_STAGE=? WHERE ID=?",
                stage, draft.getId());
        return draft.getId();
    }

    /** TrialPvtDiff 活跃节点 */
    private String activePvtDiffTaskId(String trialNo) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE='TrialPvtDiff' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, trialNo);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convert(String trialNo, String bomId, String routeId, boolean ack) {
        return trialOrderService.createFromApply(trialNo, bomId, routeId, ack);
    }

    @Test
    void applyValidationSubmitAndApproval() {
        MdmItem product = productWithBom("申请审批产品");

        // 缺阶段 422
        var noStage = assertThrows(ServiceException.class,
                () -> mkApply(product, null, "验证散热", "5"));
        assertEquals(422, noStage.getCode(), "缺阶段 422：" + noStage.getMessage());
        assertTrue(noStage.getMessage().contains("试产阶段"), noStage.getMessage());

        // 空目标 422（FR-4.5-8-1 退回补充）
        var noGoal = assertThrows(ServiceException.class,
                () -> mkApply(product, "EVT", "   ", "5"));
        assertEquals(422, noGoal.getCode(), "空目标 422：" + noGoal.getMessage());
        assertTrue(noGoal.getMessage().contains("验证目标"), noGoal.getMessage());

        // 数量 <= 0 422
        var badQty = assertThrows(ServiceException.class,
                () -> mkApply(product, "EVT", "验证散热设计", "0"));
        assertEquals(422, badQty.getCode(), "数量 422：" + badQty.getMessage());

        // 正常建档：TA- 编号 + DRAFT
        TrialApply ok = mkApply(product, "EVT", "验证散热设计", "20");
        assertTrue(ok.getTrialNo().startsWith("TA-"), "TA- 编号：" + ok.getTrialNo());
        assertEquals("DRAFT", ok.getStatus());
        assertEquals("EVT", ok.getTrialStage());

        // 提交 → PENDING + 审批实例（单节点工艺主管）
        TrialApply submitted = trialApplyService.submit(ok.getTrialNo());
        assertEquals("PENDING", submitted.getStatus(), "提交后 PENDING");
        assertNotNull(submitted.getSubmitBy(), "提交人留痕");
        assertNotNull(submitted.getApprovalId(), "审批实例回写");
        var dup = assertThrows(ServiceException.class,
                () -> trialApplyService.submit(ok.getTrialNo()));
        assertEquals(422, dup.getCode(), "重复提交 422：" + dup.getMessage());

        // 工艺主管通过 → APPROVED（回调链）
        String taskId = activeApplyTaskId(ok.getTrialNo());
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意试产");
        TrialApply approved = trialApplyService.get(ok.getTrialNo());
        assertEquals("APPROVED", approved.getStatus(), "通过 → APPROVED（回调链）");
        assertNotNull(approved.getApproveBy(), "审批人留痕");

        // 驳回 → 回 DRAFT 意见留痕（另起一单）
        TrialApply second = mkApply(product, "DVT", "验证结构强度", "10");
        trialApplyService.submit(second.getTrialNo());
        String t2 = activeApplyTaskId(second.getTrialNo());
        approvalEngine.reject(t2, "目标不明确");
        TrialApply rejected = trialApplyService.get(second.getTrialNo());
        assertEquals("DRAFT", rejected.getStatus(), "驳回回 DRAFT");
        assertEquals("目标不明确", rejected.getRejectReason(), "驳回意见留痕");
        // 回草稿可重提
        TrialApply resubmitted = trialApplyService.submit(second.getTrialNo());
        assertEquals("PENDING", resubmitted.getStatus(), "回草稿可重新提交");
    }

    // ================= 2.3 1:1 转开 =================

    @Test
    void convertOneToOneAndDuplicateBlocked() {
        MdmItem product = productWithBom("转开产品");
        MoSeed.publishRouting(routingService, operationService, workCenterService,
                standardService, jdbc, product, 2);
        String trialNo = approvedApply(product, "EVT", "20", "验证散热设计");

        // 正式 BOM/路线无确认 → 提示确认（C-4.5-07 同类），ack 后建单
        var needAck = assertThrows(ServiceException.class,
                () -> convert(trialNo, null, null, false));
        assertEquals(422, needAck.getCode(), "无 ack 422：" + needAck.getMessage());
        assertTrue(needAck.getMessage().contains("确认继续"), needAck.getMessage());

        Map<String, Object> out = convert(trialNo, null, null, true);
        MrpMo mo = (MrpMo) out.get("mo");
        assertTrue(mo.getMoNo().startsWith("TP-"), "TP- 编号：" + mo.getMoNo());
        assertEquals("TRIAL", mo.getMoType(), "MO_TYPE=TRIAL");
        assertEquals("EVT", mo.getTrialStage(), "阶段带出");
        assertEquals("APPLY", mo.getSrcType(), "来源类型 APPLY");
        assertEquals(trialNo, mo.getSrcNo(), "来源单号 = 申请单号");
        assertEquals(0, mo.getQty().compareTo(new BigDecimal("20")), "数量带出");
        assertNotNull(mo.getSourceBomId(), "BOM 快照来源回写");
        assertNotNull(mo.getSourceRoutingId(), "路线快照来源回写");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) out.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("齐套率")), "齐套预检提示：" + warnings);
        // BOM/工序快照行落库
        Integer bomRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo_bom WHERE MO_ID=?", Integer.class, mo.getId());
        assertEquals(1, bomRows, "BOM 快照行 1 行");
        Integer opRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo_op WHERE MO_ID=?", Integer.class, mo.getId());
        assertEquals(1, opRows, "工序快照行 1 行");
        // 申请单 CONVERTED 回写
        TrialApply converted = trialApplyService.get(trialNo);
        assertEquals("CONVERTED", converted.getStatus(), "申请单转开后 CONVERTED");
        assertEquals(mo.getMoNo(), converted.getMoNo(), "回写工单号");

        // 重复转开 422（1:1）
        var dup = assertThrows(ServiceException.class,
                () -> convert(trialNo, null, null, true));
        assertEquals(422, dup.getCode(), "重复转开 422：" + dup.getMessage());
        assertTrue(dup.getMessage().contains("至多一张"), dup.getMessage());

        // 非 APPROVED 转开 422（DRAFT 申请单）
        // 3.8 阶段闸口前置：EVT 试产工单须评审 APPROVED 才可申请 DVT（补总结通过再建 DVT 申请）
        String evMoId = jdbc.queryForObject(
                "SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE=? AND MO_TYPE='TRIAL'"
                        + " AND TRIAL_STAGE='EVT' LIMIT 1", String.class, product.getItemCode());
        jdbc.update("INSERT INTO erp_mrp_trial_summary (ID, SUMMARY_NO, MO_ID, MO_NO,"
                        + " TRIAL_STAGE, STATUS) VALUES (?,?,?,?,?, 'APPROVED')",
                "ts-gate-" + (System.nanoTime() % 100000), "TS-GATE-1", evMoId,
                mo.getMoNo(), "EVT");
        TrialApply draft = mkApply(product, "DVT", "验证结构", "5");
        var notApproved = assertThrows(ServiceException.class,
                () -> convert(draft.getTrialNo(), null, null, true));
        assertEquals(422, notApproved.getCode(), "非 APPROVED 422：" + notApproved.getMessage());
        assertTrue(notApproved.getMessage().contains("审批通过"), notApproved.getMessage());
    }

    // ================= 2.4 C-4.5-14 建单卡控 =================

    @Test
    void cardControlsBomAndRoute() {
        // A) 未审批试产 BOM（无 TRIAL/已发布可用）→ C-4.5-14 阻断
        MdmItem p1 = mkMake("卡控未审批BOM产品");
        MdmItem c1 = mkMake("卡控未审批BOM子件");
        mkTrialBom(p1, c1, "1", "0", "EVT", false); // DRAFT + TRIAL_STAGE（未审批）
        String n1 = approvedApply(p1, "EVT", "5", "验证卡控A");
        var unapproved = assertThrows(ServiceException.class,
                () -> convert(n1, null, null, false));
        assertEquals(422, unapproved.getCode(), "未审批试产 BOM 422：" + unapproved.getMessage());
        assertTrue(unapproved.getMessage().contains("试产 BOM 未审批"), unapproved.getMessage());
        assertTrue(unapproved.getMessage().contains("C-4.5-14"), unapproved.getMessage());

        // B) 误选正式 BOM 无确认 → 提示确认；ack 后成功留痕
        MdmItem p2 = productWithBom("卡控正式BOM产品");
        MoSeed.publishRouting(routingService, operationService, workCenterService,
                standardService, jdbc, p2, 2);
        String n2 = approvedApply(p2, "EVT", "6", "验证卡控B");
        var needAck = assertThrows(ServiceException.class,
                () -> convert(n2, null, null, false));
        assertEquals(422, needAck.getCode(), "正式 BOM 无确认 422：" + needAck.getMessage());
        assertTrue(needAck.getMessage().contains("确认继续"), needAck.getMessage());
        Map<String, Object> ok = convert(n2, null, null, true);
        MrpMo mo2 = (MrpMo) ok.get("mo");
        assertTrue(mo2.getMoNo().startsWith("TP-"), "ack 后建单成功");
        @SuppressWarnings("unchecked")
        List<String> warns = (List<String>) ok.get("warnings");
        assertTrue(warns.stream().anyMatch(w -> w.contains("确认使用正式 BOM")), "确认留痕：" + warns);

        // C) 产品无任何路线 → 阻断提示先编制试产路线
        MdmItem p3 = productWithBom("卡控无路线产品");
        String n3 = approvedApply(p3, "EVT", "3", "验证卡控C");
        var noRoute = assertThrows(ServiceException.class,
                () -> convert(n3, null, null, true));
        assertEquals(422, noRoute.getCode(), "无路线 422：" + noRoute.getMessage());
        assertTrue(noRoute.getMessage().contains("先编制试产路线"), noRoute.getMessage());
    }

    // ================= 2.5 PVT 一致性 diff =================

    @Test
    void pvtDiffApprovalGate() {
        // 试产版与已发布量产版用量/损耗不一致 → 2 项差异入审批，通过后建单
        MdmItem product = mkMake("PVT差异产品");
        MdmItem child = mkMake("PVT差异子件");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        String trialBomId = mkTrialBom(product, child, "2", "10", "PVT", true);
        mkTrialRoute(product, "PVT");
        String trialNo = approvedApply(product, "PVT", "10", "PVT 一致性验证");

        Map<String, Object> gate = convert(trialNo, trialBomId, null, true);
        assertEquals(Boolean.TRUE, gate.get("pendingDiff"), "差异待审批：" + gate);
        assertEquals(2, gate.get("diffCount"), "两项差异（用量+损耗率）：" + gate);
        assertNotNull(gate.get("apprNo"), "审批实例号返回");
        Integer moCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' AND SRC_NO=?",
                Integer.class, trialNo);
        assertEquals(0, moCount, "待审批零工单");

        // PENDING 重复调用 → 返回同一实例不重复发起
        Map<String, Object> again = convert(trialNo, trialBomId, null, true);
        assertEquals(Boolean.TRUE, again.get("pendingDiff"));
        assertEquals(gate.get("apprNo"), again.get("apprNo"), "PENDING 幂等同一实例");

        // 工艺主管通过 → 重试建单成功
        String taskId = activePvtDiffTaskId(trialNo);
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "差异确认放行");
        MoTestAuth.login("trial-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        Map<String, Object> created = convert(trialNo, trialBomId, null, true);
        assertNotNull(created.get("mo"), "审批通过后建单成功：" + created);
        MrpMo mo = (MrpMo) created.get("mo");
        assertTrue(mo.getMoNo().startsWith("TP-"));

        // 驳回路径 → 重新发起仍零工单
        MdmItem p2 = mkMake("PVT驳回产品");
        MdmItem c2 = mkMake("PVT驳回子件");
        MoSeed.publishBom(bomService, jdbc, p2, c2, "1", "0");
        String trialBom2 = mkTrialBom(p2, c2, "3", "20", "PVT", true);
        mkTrialRoute(p2, "PVT");
        String n2 = approvedApply(p2, "PVT", "5", "PVT 驳回路径");
        Map<String, Object> g2 = convert(n2, trialBom2, null, true);
        assertEquals(Boolean.TRUE, g2.get("pendingDiff"));
        String t2 = activePvtDiffTaskId(n2);
        MoTestAuth.login("proc-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.reject(t2, "差异不可接受");
        MoTestAuth.login("trial-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        Integer mo2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo WHERE MO_TYPE='TRIAL' AND SRC_NO=?",
                Integer.class, n2);
        assertEquals(0, mo2, "驳回后零工单");
        // 驳回后重试 → 重新发起审批（修订后重试语义）
        Map<String, Object> retry = convert(n2, trialBom2, null, true);
        assertEquals(Boolean.TRUE, retry.get("pendingDiff"), "驳回后重试重新发起：" + retry);
        assertNotEquals(g2.get("apprNo"), retry.get("apprNo"), "驳回后为新实例");

        // EVT 阶段不触发 diff（spec：EVT/DVT 允许未冻结试产版）
        MdmItem p3 = mkMake("EVT免检产品");
        MdmItem c3 = mkMake("EVT免检子件");
        MoSeed.publishBom(bomService, jdbc, p3, c3, "1", "0");
        String trialBom3 = mkTrialBom(p3, c3, "9", "50", "EVT", true);
        mkTrialRoute(p3, "EVT");
        String n3 = approvedApply(p3, "EVT", "4", "EVT 免 diff");
        Map<String, Object> ev = convert(n3, trialBom3, null, true);
        assertNotNull(ev.get("mo"), "EVT 与量产版不一致直接放行：" + ev);
    }

    // ================= 2.6 生命周期与变更边界 =================

    @Test
    void trialSplitBannedHoldCancelApply() {
        MdmItem product = productWithBom("试产变更边界产品");
        MoSeed.publishRouting(routingService, operationService, workCenterService,
                standardService, jdbc, product, 2);
        String trialNo = approvedApply(product, "EVT", "12", "变更边界验证");
        MrpMo mo = (MrpMo) convert(trialNo, null, null, true).get("mo");
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        MrpMo released = moService.release(mo.getId());
        assertEquals(MrpMo.ST_RELEASED, released.getStatus(), "试产单走既有释放链");

        // 禁拆分（work-order delta：量由申请单锚定）
        var split = assertThrows(ServiceException.class,
                () -> moService.split(mo.getId(), List.of(new BigDecimal("6")), "拆两半"));
        assertEquals(422, split.getCode(), "试产单禁拆分 422：" + split.getMessage());
        assertTrue(split.getMessage().contains("试产工单不支持拆分"), split.getMessage());

        // 挂起必填原因 → 挂起成功 → 恢复 → 取消（挂起/取消照常适用）
        var noReason = assertThrows(ServiceException.class,
                () -> moService.hold(mo.getId(), " "));
        assertEquals(422, noReason.getCode(), "挂起必填原因");
        MrpMo held = moService.hold(mo.getId(), "等待工程样品到料");
        assertEquals(MrpMo.ST_HOLD, held.getStatus(), "挂起成功");
        MrpMo resumed = moService.resume(mo.getId());
        assertEquals(MrpMo.ST_RELEASED, resumed.getStatus(), "恢复成功");
        MrpMo cancelled = moService.cancel(mo.getId(), "试产计划取消");
        assertEquals(MrpMo.ST_CANCELLED, cancelled.getStatus(), "取消成功");
    }

    @Test
    void phaseDerivationMatrix() {
        MdmItem product = productWithBom("阶段推导产品");
        MoSeed.publishRouting(routingService, operationService, workCenterService,
                standardService, jdbc, product, 2);
        String trialNo = approvedApply(product, "EVT", "8", "阶段推导验证");
        MrpMo mo = (MrpMo) convert(trialNo, null, null, true).get("mo");
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        moService.release(mo.getId());

        // 未完工 → 执行中
        assertEquals("EXECUTING", trialOrderService.phaseOf(mo.getId()), "RELEASED=执行中");

        // 完工但无检验批 → 待检验
        moService.complete(mo.getId(), new BigDecimal("8"));
        assertEquals("PENDING_INSP", trialOrderService.phaseOf(mo.getId()), "完工无检验批=待检验");

        // 检验批 PENDING → 仍待检验
        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, SOURCE_TYPE,"
                        + " REF_TYPE, REF_ID, ITEM_CODE, LOT_QTY, STATUS, RESULT)"
                        + " VALUES (?,?,?,?,?,?,?,?, 'PENDING', 'PENDING')",
                "il-tr-" + (System.nanoTime() % 100000), "IL-TR-1", "OQC", "MANUAL",
                "WORKORDER", mo.getId() + "#TRIAL", product.getItemCode(), new BigDecimal("8"));
        assertEquals("PENDING_INSP", trialOrderService.phaseOf(mo.getId()), "批 PENDING=待检验");

        // 放行但无冲回分录 → 待入库
        jdbc.update("UPDATE erp_qms_inspection_lot SET STATUS='RELEASED', RESULT='PASS'"
                + " WHERE REF_ID=?", mo.getId() + "#TRIAL");
        assertEquals("PENDING_STOCK_IN", trialOrderService.phaseOf(mo.getId()), "放行无分录=待入库");

        // 冲回分录存在、无总结 → 待评审
        jdbc.update("INSERT INTO erp_mrp_trial_cost (ID, ENTRY_NO, MO_ID, MO_NO, COST_TYPE,"
                        + " AMOUNT, EXPENSE_TYPE, FIN_STATUS) VALUES (?,?,?,?,?,?,?,?)",
                "tc-tr-" + (System.nanoTime() % 100000), "TC-TR-1", mo.getId(), mo.getMoNo(),
                "REVERSAL", new BigDecimal("100"), "RD", "CONFIRMED");
        assertEquals("PENDING_REVIEW", trialOrderService.phaseOf(mo.getId()), "分录在无总结=待评审");

        // 总结 DRAFT → 仍待评审；APPROVED → 待关闭
        jdbc.update("INSERT INTO erp_mrp_trial_summary (ID, SUMMARY_NO, MO_ID, MO_NO,"
                        + " TRIAL_STAGE, STATUS) VALUES (?,?,?,?,?,?)",
                "ts-tr-" + (System.nanoTime() % 100000), "TS-TR-1", mo.getId(), mo.getMoNo(),
                "EVT", "DRAFT");
        assertEquals("PENDING_REVIEW", trialOrderService.phaseOf(mo.getId()), "总结 DRAFT=待评审");
        jdbc.update("UPDATE erp_mrp_trial_summary SET STATUS='APPROVED' WHERE MO_ID=?", mo.getId());
        assertEquals("PENDING_CLOSE", trialOrderService.phaseOf(mo.getId()), "评审过未关单=待关闭");

        // 关单 → 终态 CLOSED（前置：全工序 DONE + 3.11 结案卡控——采集/评审/归集/剩余料）
        jdbc.update("UPDATE erp_mrp_mo_op SET OP_STATUS='DONE' WHERE MO_ID=?", mo.getId());
        jdbc.update("INSERT INTO erp_mrp_trial_data (ID, MO_ID, OP_SEQ, ACTUAL_HOURS)"
                        + " VALUES (?,?,10, 1)",
                "td-tr-" + (System.nanoTime() % 100000), mo.getId());
        jdbc.update("INSERT INTO erp_mrp_trial_data (ID, MO_ID, OP_SEQ, ACTUAL_HOURS)"
                        + " VALUES (?,?,20, 1)",
                "td-tr2-" + (System.nanoTime() % 100000), mo.getId());
        jdbc.update("INSERT INTO erp_mrp_trial_cost (ID, ENTRY_NO, MO_ID, MO_NO, COST_TYPE,"
                        + " AMOUNT, EXPENSE_TYPE, FIN_STATUS, REMARK) VALUES (?,?,?,?,?,?,?,?,'TRIAL:x')",
                "tc-mat-" + (System.nanoTime() % 100000), "TC-MAT-1", mo.getId(), mo.getMoNo(),
                "MATERIAL", new BigDecimal("10"), "RD", "CONFIRMED");
        moService.close(mo.getId());
        assertEquals("CLOSED", trialOrderService.phaseOf(mo.getId()), "关单=终态");
    }
}
