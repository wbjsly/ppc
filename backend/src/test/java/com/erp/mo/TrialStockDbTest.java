package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpReporting;
import com.erp.entity.mrp.TrialApply;
import com.erp.entity.qms.InspectionLot;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.StockPostingEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.ReportingService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.TrialApplyService;
import com.erp.service.mrp.TrialOrderService;
import com.erp.service.mrp.WorkCenterService;
import com.erp.service.impl.mrp.TrialStockListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试产放行/计价冲回/财务确认库测（change add-trial-production-management，
 * spec trial-execution「放行转可用与计价冲回」四场景 + 「未放行直接入可用阻断」）：
 * 完工入样品仓 QC → 未放行引擎过账 422 → 放行平移 QTY 守恒 + REVERSAL 分录
 * （18×STANDARD_COST）→ 二调幂等零重复 → 财务确认 CONFIRMED 计入汇总。
 */
@SpringBootTest
class TrialStockDbTest {

    @Autowired
    private TrialApplyService trialApplyService;
    @Autowired
    private TrialOrderService trialOrderService;
    @Autowired
    private TrialStockListener trialStockListener;
    @Autowired
    private MoService moService;
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
    private DispatchService dispatchService;
    @Autowired
    private ReportingService reportingService;
    @Autowired
    private StockPostingEngine stockPostingEngine;
    @Autowired
    private com.erp.service.SysParamService sysParam;
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
        MoTestAuth.login("trial-stock-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");

        int n = (int) (System.nanoTime() % 100000);
        opCode = "TSOP-" + n;
        wcCode = "TSWC-" + n;
        com.erp.entity.mrp.MrpOperation op = new com.erp.entity.mrp.MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("试产放行工序");
        operationService.create(op);
        com.erp.entity.mrp.MrpWorkCenter wc = new com.erp.entity.mrp.MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("试产放行中心");
        workCenterService.create(wc);
        com.erp.entity.mrp.MrpOpWcStandard std = new com.erp.entity.mrp.MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setSetupHours(new BigDecimal("0.5"));
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);

        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('TrialApply','TrialCostReverse'))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('TrialApply','TrialCostReverse')");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'TRIAL_%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_ID LIKE '%#TRIAL'");
        jdbc.update("DELETE FROM erp_mrp_trial_cost");
        jdbc.update("DELETE FROM erp_mrp_trial_summary");
        jdbc.update("DELETE FROM erp_mrp_trial_data");
        jdbc.update("DELETE FROM erp_mrp_trial_apply");
        jdbc.update("DELETE FROM erp_mrp_feed");
        jdbc.update("DELETE FROM erp_mrp_reporting");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE IN "
                + "('TRIAL_STOCK_IN','TRIAL_GATE_TEST')");
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE='WH-SAMPLE'");
        for (String code : touchedProducts) {
            jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE=?", code);
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
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE MO_ID IS NULL OR MO_ID NOT IN "
                + "(SELECT ID FROM erp_mrp_mo)");
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE LIKE ?", opCode + "%");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE=?", wcCode);
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE ?", opCode + "%");
        MoSeed.cleanup(jdbc);
    }

    // ---------- 助手 ----------

    private MdmItem mkMake(String name) {
        MdmItem p = MoSeed.mkItem(itemService, categoryCode, name, "MAKE");
        touchedProducts.add(p.getItemCode());
        return p;
    }

    /** 双道工序已发布路线（工序码按产品派生） */
    private void publishTwoOpRoute(MdmItem product) {
        String base = opCode + "-" + product.getItemCode().substring(
                Math.max(0, product.getItemCode().length() - 6));
        List<com.erp.entity.mrp.MrpRoutingOp> rows = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String code = base + "-" + i;
            com.erp.entity.mrp.MrpOperation op = new com.erp.entity.mrp.MrpOperation();
            op.setOpCode(code);
            op.setOpName("试产放行工序" + i);
            operationService.create(op);
            com.erp.entity.mrp.MrpOpWcStandard std = new com.erp.entity.mrp.MrpOpWcStandard();
            std.setOpCode(code);
            std.setWcCode(wcCode);
            std.setSetupHours(new BigDecimal("0.5"));
            std.setRunHours(BigDecimal.ONE);
            standardService.create(std);
            com.erp.entity.mrp.MrpRoutingOp row = new com.erp.entity.mrp.MrpRoutingOp();
            row.setOpCode(code);
            row.setWcCode(wcCode);
            row.setLeadTime(BigDecimal.ONE);
            row.setStandardYield(new BigDecimal("98"));
            row.setKeyOpFlag("0");
            rows.add(row);
        }
        com.erp.entity.mrp.MrpRouting head = new com.erp.entity.mrp.MrpRouting();
        head.setItemCode(product.getItemCode());
        com.erp.entity.mrp.MrpRouting draft = routingService.create(head, rows);
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='PENDING' WHERE ID=?", draft.getId());
        routingService.publishApproved(draft.getId());
    }

    /** 申请 → 审批 → 转开 → 释放 */
    private MrpMo approvedTrialMo(MdmItem product, String stage, String qty) {
        TrialApply h = new TrialApply();
        h.setTrialStage(stage);
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setGoal("放行冲回验证");
        TrialApply a = trialApplyService.create(h);
        trialApplyService.submit(a.getTrialNo());
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE='TrialApply' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, a.getTrialNo());
        MoTestAuth.login("proc-stock", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意");
        MoTestAuth.login("trial-stock-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = trialOrderService.createFromApply(a.getTrialNo(), null, null, true);
        MrpMo mo = (MrpMo) out.get("mo");
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        return moService.release(mo.getId());
    }

    /**
     * 完工入样品仓：双工序派工报工（末道 good 18 ng 2 → 合格 18）+ 完工。
     * 返回 {mo, batchNo}。
     */
    private Map<String, Object> completeIntoSampleWh(String name) {
        MdmItem product = mkMake(name);
        MdmItem child = mkMake(name + "子件");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST=100 WHERE ITEM_CODE=?",
                product.getItemCode());
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        publishTwoOpRoute(product);
        MrpMo mo = approvedTrialMo(product, "EVT", "20");

        List<DispatchService.GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new DispatchService.GenReq(
                        (String) r.get("moId"), ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        int i = 0;
        for (MrpDispatch d : dispatchService.generate(rows)) {
            dispatchService.confirm(d.getId());
            MrpReporting r = reportingService.open(d.getId(), null);
            if (i == 1) {
                reportingService.saveOutput(r.getId(), new BigDecimal("18"), BigDecimal.ZERO,
                        null, false);
            } else {
                reportingService.saveOutput(r.getId(), new BigDecimal("20"), BigDecimal.ZERO,
                        null, false);
            }
            reportingService.confirm(r.getId());
            i++;
        }
        Map<String, Object> out = reportingService.completeMo(mo.getId());
        assertNotNull(out.get("trial"), "完工走 TRIAL 分支：" + out.keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> trial = (Map<String, Object>) out.get("trial");
        return Map.of("mo", mo, "batchNo", String.valueOf(trial.get("batchNo")));
    }

    private BigDecimal whQty(String wh, String itemCode, String col) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT IFNULL(" + col + ",0) FROM erp_inv_stock WHERE ITEM_CODE=? AND WAREHOUSE_CODE=?",
                (rs, i) -> rs.getBigDecimal(1), itemCode, wh);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    // ================= 主链 =================

    @Test
    void releaseShiftReversalIdempotentAndFinanceConfirm() {
        Map<String, Object> seed = completeIntoSampleWh("放行冲回产品");
        MrpMo mo = (MrpMo) seed.get("mo");
        String batchNo = (String) seed.get("batchNo");
        String code = mo.getProductCode();

        // ① 完工入样品仓 QC=18、AVAILABLE=0（BR-4.5-34 不产生可销售库存）
        assertEquals(0, whQty("WH-SAMPLE", code, "QC_QTY").compareTo(new BigDecimal("18")),
                "QC=18");
        assertEquals(0, whQty("WH-SAMPLE", code, "AVAILABLE_QTY").compareTo(BigDecimal.ZERO),
                "AVAILABLE=0");

        // ② 未放行 → 引擎转可用过账 422 含「未放行」（C-4.5-14 后半）
        StockPostingEngine.Line gateLine = new StockPostingEngine.Line();
        gateLine.warehouseCode = "WH-SAMPLE";
        gateLine.itemCode = code;
        gateLine.itemName = mo.getProductName();
        gateLine.batchNo = batchNo;
        gateLine.qty = BigDecimal.ONE;
        gateLine.intoQc = false;
        var blocked = assertThrows(ServiceException.class, () -> stockPostingEngine.post(
                StockPostingEngine.Request.of("WIP_IN", "TRIAL_GATE_TEST", "GATE-1",
                        List.of(gateLine))));
        assertEquals(422, blocked.getCode(), "未放行阻断 422：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("未放行"), blocked.getMessage());

        // ③ 放行（同 confirmRelease 回调路径——直接驱动监听器同一入口）
        jdbc.update("UPDATE erp_qms_inspection_lot SET RESULT='PASS', STATUS='RELEASED' "
                + "WHERE REF_ID=?", mo.getId() + "#TRIAL");
        InspectionLot lot = jdbc.queryForObject(
                "SELECT * FROM erp_qms_inspection_lot WHERE REF_ID=?",
                (rs, i) -> {
                    InspectionLot l = new InspectionLot();
                    l.setId(rs.getString("ID"));
                    l.setRefType(rs.getString("REF_TYPE"));
                    l.setRefId(rs.getString("REF_ID"));
                    l.setItemCode(rs.getString("ITEM_CODE"));
                    l.setBatchNo(rs.getString("BATCH_NO"));
                    l.setLotNo(rs.getString("LOT_NO"));
                    l.setLotQty(rs.getBigDecimal("LOT_QTY"));
                    l.setResult(rs.getString("RESULT"));
                    l.setStatus(rs.getString("STATUS"));
                    return l;
                },
                mo.getId() + "#TRIAL");
        trialStockListener.onReleased(lot);

        // 平移 QTY 守恒：QC 18→0、AVAILABLE 0→18、QTY 不变
        assertEquals(0, whQty("WH-SAMPLE", code, "QC_QTY").compareTo(BigDecimal.ZERO), "QC 归零");
        assertEquals(0, whQty("WH-SAMPLE", code, "AVAILABLE_QTY").compareTo(new BigDecimal("18")),
                "AVAILABLE=18");
        assertEquals(0, whQty("WH-SAMPLE", code, "QTY").compareTo(new BigDecimal("18")),
                "QTY 守恒 18");

        // REVERSAL 分录：金额 = 18 × STANDARD_COST(100) = 1800，PENDING_FINANCE + 关联留痕
        Map<String, Object> rev = jdbc.queryForMap(
                "SELECT AMOUNT, FIN_STATUS, DR_CR, BATCH_NO, QTY, LOT_ID, MO_NO, APPROVAL_ID "
                        + "FROM erp_mrp_trial_cost WHERE MO_ID=? AND COST_TYPE='REVERSAL'",
                mo.getId());
        assertEquals(0, new BigDecimal(String.valueOf(rev.get("AMOUNT")))
                .compareTo(new BigDecimal("1800")), "分录金额 18×100=1800：" + rev.get("AMOUNT"));
        assertEquals("PENDING_FINANCE", rev.get("FIN_STATUS"), "待财务确认");
        assertEquals("DR存货/CR研发费用", rev.get("DR_CR"), "借贷方向留痕");
        assertEquals(batchNo, rev.get("BATCH_NO"), "批次关联");
        assertEquals(0, new BigDecimal(String.valueOf(rev.get("QTY")))
                .compareTo(new BigDecimal("18")), "数量关联 18");
        assertNotNull(rev.get("LOT_ID"), "检验批幂等锚");
        assertEquals(mo.getMoNo(), rev.get("MO_NO"), "工单号反查");
        assertNotNull(rev.get("APPROVAL_ID"), "已送 TrialCostReverse 审批");

        // 确认前汇总：冲回不计入 net（PENDING 剔除）
        Map<String, Object> before = trialOrderService.costOf(mo.getId());
        assertEquals(0, ((BigDecimal) before.get("reversalPending"))
                .compareTo(new BigDecimal("1800")), "PENDING 计入待确认");
        assertEquals(0, ((BigDecimal) before.get("reversalConfirmed")).compareTo(BigDecimal.ZERO),
                "未确认不入汇总");
        assertEquals(0, ((BigDecimal) before.get("netTotal"))
                .compareTo((BigDecimal) before.get("collectedTotal")), "net = 归集合计（冲回未扣）");

        // ④ 二次放行幂等：零平移零重复分录
        trialStockListener.onReleased(lot);
        assertEquals(0, whQty("WH-SAMPLE", code, "AVAILABLE_QTY").compareTo(new BigDecimal("18")),
                "幂等后 AVAILABLE 仍 18");
        Long revCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_trial_cost WHERE MO_ID=? AND COST_TYPE='REVERSAL'",
                Long.class, mo.getId());
        assertEquals(1L, revCount, "分录不重复");

        // ⑤ 财务确认 → CONFIRMED + 签署留痕 + 汇总计入
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE='TrialCostReverse' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, costRowId(mo.getId()));
        MoTestAuth.login("fin-mgr", "ROLE_FINANCE_MGR");
        approvalEngine.pass(taskId, "确认冲回金额");
        MoTestAuth.login("trial-stock-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT FIN_STATUS, CONFIRM_BY, CONFIRM_AT FROM erp_mrp_trial_cost "
                        + "WHERE MO_ID=? AND COST_TYPE='REVERSAL'", mo.getId());
        assertEquals("CONFIRMED", after.get("FIN_STATUS"), "财务确认后 CONFIRMED");
        assertEquals("fin-mgr", after.get("CONFIRM_BY"), "签署人留痕");
        assertNotNull(after.get("CONFIRM_AT"), "签署时间留痕");

        Map<String, Object> sum = trialOrderService.costOf(mo.getId());
        assertEquals(0, ((BigDecimal) sum.get("reversalConfirmed"))
                .compareTo(new BigDecimal("1800")), "确认后冲回计入汇总");
        assertEquals(0, ((BigDecimal) sum.get("reversalPending")).compareTo(BigDecimal.ZERO),
                "PENDING 清零");
        assertEquals(0, ((BigDecimal) sum.get("netTotal")).compareTo(
                ((BigDecimal) sum.get("collectedTotal")).subtract(new BigDecimal("1800"))),
                "net = 归集 − 已确认冲回");

        // ⑥ 放行后同批转可用放行（检验批 RELEASED → 闸门打开）
        stockPostingEngine.post(StockPostingEngine.Request.of("WIP_IN", "TRIAL_GATE_TEST",
                "GATE-2", List.of(gateLine)));
        assertEquals(0, whQty("WH-SAMPLE", code, "AVAILABLE_QTY").compareTo(new BigDecimal("19")),
                "放行后引擎过账放行（18+1）");
    }

    /** 成本行 ID（bizId）——按工单唯一 REVERSAL 行取 */
    private String costRowId(String moId) {
        return jdbc.queryForObject(
                "SELECT ID FROM erp_mrp_trial_cost WHERE MO_ID=? AND COST_TYPE='REVERSAL'",
                String.class, moId);
    }
}
