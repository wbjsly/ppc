package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.TrialApply;
import com.erp.entity.mrp.TrialData;
import com.erp.entity.mrp.TrialSummary;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.TrialApplyService;
import com.erp.service.mrp.TrialDataService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试产执行链库测（change add-trial-production-management，trial-execution 族）。
 * 3.5 数据采集与结案卡控（带出 5.7 工时 / 缺记录关单 422 列工序 / 补录后通过）；
 * 后续 3.6~3.11 用例随任务追加。
 */
@SpringBootTest
class TrialExecutionDbTest {

    @Autowired
    private TrialApplyService trialApplyService;
    @Autowired
    private TrialOrderService trialOrderService;
    @Autowired
    private TrialDataService trialDataService;
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
    private com.erp.service.mrp.DispatchService dispatchService;
    @Autowired
    private com.erp.service.mrp.ReportingService reportingService;
    @Autowired
    private com.erp.service.mrp.TrialSummaryService trialSummaryService;
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
        MoTestAuth.login("trial-exec-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");

        int n = (int) (System.nanoTime() % 100000);
        opCode = "TEOP-" + n;
        wcCode = "TEWC-" + n;
        com.erp.entity.mrp.MrpOperation op = new com.erp.entity.mrp.MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("试产执行工序");
        operationService.create(op);
        com.erp.entity.mrp.MrpWorkCenter wc = new com.erp.entity.mrp.MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("试产执行中心");
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
                + "('TrialApply','TrialReview','TrialPvtDiff'))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('TrialApply','TrialReview','TrialPvtDiff')");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'TRIAL_%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_ID LIKE '%#TRIAL'");
        jdbc.update("DELETE FROM erp_mrp_trial_cost");
        jdbc.update("DELETE FROM erp_mrp_trial_summary");
        jdbc.update("DELETE FROM erp_mrp_trial_data");
        jdbc.update("DELETE FROM erp_mrp_trial_apply");
        jdbc.update("DELETE FROM erp_mrp_feed");
        jdbc.update("DELETE FROM erp_mrp_reporting");
        // 3.11 关单剩余料造数（领料/批次/库存按试产批次前缀清理）
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID LIKE 'iss-close-%'");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_NO LIKE 'MI-CLOSE-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE BATCH_NO='TR-REM-1'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE BATCH_NO='TR-REM-1'");
        // 3.6 完工分支副作用：试产入库流水/样品仓库存/产出批次
        jdbc.update("DELETE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='TRIAL_STOCK_IN'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE='WH-SAMPLE'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_CODE IN "
                + "(SELECT DISTINCT PRODUCT_CODE FROM erp_mrp_mo WHERE MO_NO LIKE 'TP-%'))");
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE MO_ID IN "
                + "(SELECT ID FROM erp_mrp_mo WHERE MO_NO LIKE 'TP-%')");
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

    private MdmItem productWithBom(String name) {
        MdmItem p = mkMake(name);
        MdmItem child = mkMake(name + "子件");
        MoSeed.publishBom(bomService, jdbc, p, child, "1", "0");
        return p;
    }

    /** 双道工序的已发布路线（采集按工序 → 需 ≥2 道 DONE 工序）；工序码按产品派生支持一测多产品 */
    private void publishTwoOpRoute(MdmItem product) {
        String base = opCode + "-" + product.getItemCode().substring(
                Math.max(0, product.getItemCode().length() - 6));
        List<com.erp.entity.mrp.MrpRoutingOp> rows = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String code = base + "-" + i;
            com.erp.entity.mrp.MrpOperation op = new com.erp.entity.mrp.MrpOperation();
            op.setOpCode(code);
            op.setOpName("试产执行工序" + i);
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

    /** 申请 → 审批 → 转开 → 释放；返回 TP- 工单 */
    private MrpMo approvedTrialMo(MdmItem product, String stage, String qty, String goal) {
        TrialApply h = new TrialApply();
        h.setTrialStage(stage);
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setGoal(goal);
        TrialApply a = trialApplyService.create(h);
        trialApplyService.submit(a.getTrialNo());
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE='TrialApply' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, a.getTrialNo());
        MoTestAuth.login("proc-exec", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意试产");
        MoTestAuth.login("trial-exec-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        Map<String, Object> out = trialOrderService.createFromApply(
                a.getTrialNo(), null, null, true);
        MrpMo mo = (MrpMo) out.get("mo");
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        return moService.release(mo.getId());
    }

    private String activeTaskId(String bizType, String bizId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE=? AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, bizType, bizId);
    }

    /** 三并行节点下按角色取活动任务（TrialReview 单 SEQ 三节点） */
    private String activeTaskId(String bizType, String bizId, String role) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE=? AND a.BIZ_ID=? AND t.STATUS='ACTIVE' AND t.ROLE_REQUIRED=?",
                String.class, bizType, bizId, role);
    }

    /** 样品仓某数量列（无行 = 0） */
    private BigDecimal sampleWhQty(String itemCode, String col) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT IFNULL(" + col + ",0) FROM erp_inv_stock WHERE ITEM_CODE=?"
                        + " AND WAREHOUSE_CODE='WH-SAMPLE'",
                (rs, i) -> rs.getBigDecimal(1), itemCode);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.get(0);
    }

    // ================= 3.5 数据采集与结案卡控 =================

    @Test
    void dataCollectionPrefillAndCloseGate() {
        MdmItem product = productWithBom("采集结案产品");
        publishTwoOpRoute(product);
        MrpMo mo = approvedTrialMo(product, "EVT", "10", "采集结案验证");
        moService.complete(mo.getId(), new BigDecimal("8"));
        // 两道工序 DONE（报工推进的简化：JDBC 置 DONE 聚焦采集卡控本身）
        jdbc.update("UPDATE erp_mrp_mo_op SET OP_STATUS='DONE' WHERE MO_ID=?", mo.getId());
        Integer opCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo_op WHERE MO_ID=? AND OP_STATUS='DONE'",
                Integer.class, mo.getId());
        assertTrue(opCount >= 2, "双道 DONE 工序前置：" + opCount);

        // 未报工 → 预填为空（prefilled=false）
        Map<String, Object> f1 = trialDataService.form(mo.getId(), 10);
        TrialData d1 = (TrialData) f1.get("data");
        assertEquals(10, d1.getOpSeq());
        assertEquals(Boolean.FALSE, f1.get("prefilled"), "无报工不预填工时");
        assertEquals("试产执行工序0", d1.getOpName(), "工序名带出");

        // 造 5.7 CONFIRMED 报工（setup 0.5 + run 1 = 1.5h）→ 带出 1.5（偏差 D7 场景）
        jdbc.update("INSERT INTO erp_mrp_reporting (ID, RPT_NO, DISPATCH_ID, MO_ID, MO_NO, OP_SEQ,"
                        + " OP_NAME, STATUS, SETUP_HOURS, RUN_HOURS) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "rpt-te-" + (System.nanoTime() % 100000), "RPT-TE-" + (System.nanoTime() % 100000),
                "dp-te-" + (System.nanoTime() % 100000), mo.getId(), mo.getMoNo(),
                10, "试产执行工序0", "CONFIRMED", new BigDecimal("0.5"), BigDecimal.ONE);
        Map<String, Object> f2 = trialDataService.form(mo.getId(), 10);
        TrialData d2 = (TrialData) f2.get("data");
        assertEquals(Boolean.TRUE, f2.get("prefilled"), "有报工预填");
        assertEquals(0, d2.getActualHours().compareTo(new BigDecimal("1.5")),
                "带出 = setup+run 合计 1.5h：" + d2.getActualHours());

        // 只录 op10 → 缺 op20 → 关单 422 列出工序
        TrialData rec = new TrialData();
        rec.setMoId(mo.getId());
        rec.setOpSeq(10);
        rec.setActualHours(new BigDecimal("1.5"));
        rec.setDefectModes("散热不良 1 台");
        trialDataService.save(rec);
        var blocked = assertThrows(ServiceException.class, () -> moService.close(mo.getId()));
        assertEquals(422, blocked.getCode(), "缺采集关单 422：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("试产数据缺记录"), blocked.getMessage());
        assertTrue(blocked.getMessage().contains("工序 20"), "列出缺记录工序：" + blocked.getMessage());

        // 补录 op20 → 关单通过（FR-4.5-8-5 场景「补录后通过」）
        TrialData rec2 = new TrialData();
        rec2.setMoId(mo.getId());
        rec2.setOpSeq(20);
        rec2.setActualHours(BigDecimal.ONE);
        trialDataService.save(rec2);
        // upsert 幂等：重复保存 op10 走更新不报错
        TrialData again = trialDataService.save(rec);
        assertNotNull(again.getId());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_trial_data WHERE MO_ID=?", Integer.class, mo.getId());
        assertEquals(2, rows, "upsert 不重复插行");

        // 3.11 关单预检扩展：评审 APPROVED + 成本归集为结案前置（补前置后关单通过）
        TrialSummary sum = new TrialSummary();
        sum.setMoId(mo.getId());
        approveSummary(trialSummaryService.save(sum).getSummaryNo());
        trialOrderService.onCompleted(mo, new BigDecimal("8")); // 归集 MATERIAL 行（幂等）

        MrpMo closed = moService.close(mo.getId());
        assertEquals(MrpMo.ST_CLOSED, closed.getStatus(), "补录后关单成功（终态）");
        assertEquals(2, trialDataService.listByMo(mo.getId()).size(), "采集行可查");
    }

    // ================= 3.6 完工 TRIAL 分支（design D7） =================

    @Test
    void completionEntersSampleQcAndAggregatesCost() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("TRIAL_LABOR_RATE", "60", "NUMBER", "MRP", "测试费率", "test");
        sysParam.setValue("TRIAL_MACHINE_RATE", "100", "NUMBER", "MRP", "测试费率", "test");
        try {
            MdmItem product = productWithBom("完工入库试产产品");
            publishTwoOpRoute(product);
            // 子件（BOM 行）标准成本 = 100；子件可用库存供倒冲
            String childCode = jdbc.queryForObject(
                    "SELECT bi.ITEM_CODE FROM erp_mrp_bom_item bi JOIN erp_mrp_bom b ON bi.BOM_ID=b.ID "
                            + "WHERE b.PARENT_ITEM_CODE=? LIMIT 1", String.class, product.getItemCode());
            jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST=100 WHERE ITEM_CODE=?", childCode);
            MoSeed.mkStock(jdbc, childCode, "9999");

            MrpMo mo = approvedTrialMo(product, "EVT", "10", "完工入库验证");

            // 派工两道工序
            List<com.erp.service.mrp.DispatchService.GenReq> rows = dispatchService.pending(null).stream()
                    .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                    .map(r -> new com.erp.service.mrp.DispatchService.GenReq(
                            (String) r.get("moId"), ((Number) r.get("opSeq")).intValue(), null))
                    .toList();
            assertEquals(2, rows.size(), "试产双工序派工行");
            List<com.erp.entity.mrp.MrpDispatch> ds = dispatchService.generate(rows);
            for (com.erp.entity.mrp.MrpDispatch d : ds) {
                dispatchService.confirm(d.getId());
                com.erp.entity.mrp.MrpReporting r = reportingService.open(d.getId(), null);
                reportingService.saveHours(r.getId(), new BigDecimal("0.5"), BigDecimal.ONE,
                        new BigDecimal("2")); // 设备 2h → 费用 2×100=200
                reportingService.saveOutput(r.getId(), new BigDecimal("8"), BigDecimal.ZERO,
                        null, false);
                reportingService.confirm(r.getId());
            }

            // 采集表录实测工时（工费权威源，design D7）
            TrialData d10 = new TrialData();
            d10.setMoId(mo.getId());
            d10.setOpSeq(10);
            d10.setActualHours(new BigDecimal("1.5"));
            trialDataService.save(d10);
            TrialData d20 = new TrialData();
            d20.setMoId(mo.getId());
            d20.setOpSeq(20);
            d20.setActualHours(BigDecimal.ONE);
            trialDataService.save(d20);

            BigDecimal availBefore = sampleWhQty(product.getItemCode(), "AVAILABLE_QTY");
            Map<String, Object> out = reportingService.completeMo(mo.getId());
            assertEquals("COMPLETED", out.get("status"), "完工推进");
            assertNotNull(out.get("trial"), "TRIAL 分支返回：" + out.keySet());
            @SuppressWarnings("unchecked")
            Map<String, Object> trial = (Map<String, Object>) out.get("trial");
            assertEquals(Boolean.TRUE, trial.get("stockIn"), "样品仓入库过账");
            assertEquals("WH-SAMPLE", trial.get("warehouse"), "入样品仓");

            // ① 样品仓 QC 增加、AVAILABLE 不变（BR-4.5-34 不产生可销售库存）
            BigDecimal qc = sampleWhQty(product.getItemCode(), "QC_QTY");
            BigDecimal availAfter = sampleWhQty(product.getItemCode(), "AVAILABLE_QTY");
            assertEquals(0, qc.compareTo(new BigDecimal("8")), "QC_QTY=合格 8：" + qc);
            assertEquals(0, availAfter.compareTo(availBefore), "AVAILABLE 不变（未放行不可销售）");

            // ② WIP_IN 流水生成
            Long txn = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='TRIAL_STOCK_IN'"
                            + " AND BIZ_DOC_NO=?", Long.class, mo.getMoNo());
            assertTrue(txn >= 1, "TRIAL_STOCK_IN 流水生成：" + txn);

            // ③ OQC 批 refId=<moId>#TRIAL 建立
            Long lots = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_ID=?",
                    Long.class, mo.getId() + "#TRIAL");
            assertEquals(1L, lots, "OQC 试产检验批 1 个");

            // ④ 归集 MATERIAL/LABOR/OVERHEAD 三行（料=10×100、工=2.5h×60、费=2h×100）
            Map<String, BigDecimal> costs = new java.util.HashMap<>();
            for (Map<String, Object> row : jdbc.queryForList(
                    "SELECT COST_TYPE, AMOUNT FROM erp_mrp_trial_cost WHERE MO_ID=?"
                            + " AND REMARK LIKE 'TRIAL:%'", mo.getId())) {
                costs.put((String) row.get("COST_TYPE"), (BigDecimal) row.get("AMOUNT"));
            }
            assertEquals(3, costs.size(), "三类归集行：" + costs.keySet());
            assertEquals(0, costs.get("MATERIAL").compareTo(new BigDecimal("1000")),
                    "材料 = BFLUSH 10 × 子件标准成本 100：" + costs.get("MATERIAL"));
            assertEquals(0, costs.get("LABOR").compareTo(new BigDecimal("150")),
                    "工费 = 2.5h × 60：" + costs.get("LABOR"));
            assertEquals(0, costs.get("OVERHEAD").compareTo(new BigDecimal("400")),
                    "制费 = 两工序各 2h 共 4h × 100：" + costs.get("OVERHEAD"));

            // ⑤ 倒冲照常执行（TRIAL 无豁免条款，design D7）
            BigDecimal bflush = jdbc.queryForObject(
                    "SELECT IFNULL(SUM(QTY),0) FROM erp_mrp_feed WHERE MO_ID=? AND FLOW_TYPE='BFLUSH'",
                    BigDecimal.class, mo.getId());
            assertEquals(0, bflush.compareTo(new BigDecimal("10")), "BFLUSH 补投 10：" + bflush);
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        }
    }

    // ================= 3.7 总结单与跨部门评审（FR-4.5-8-6 / D10） =================

    /** 造一张评审就绪的试产单：双工序派工报工（末道 good/ng 决定良率）+ 采集 3.6h */
    private MrpMo readyForSummary(String name) {
        return readyForSummary(name, "EVT", "10", "9", "1");
    }

    private MrpMo readyForSummary(String name, String stage, String qty, String lastGood, String lastNg) {
        MdmItem product = productWithBom(name);
        publishTwoOpRoute(product);
        // 子件库存（齐套 C-4.5-11 派工前置）
        String childCode = jdbc.queryForObject(
                "SELECT bi.ITEM_CODE FROM erp_mrp_bom_item bi JOIN erp_mrp_bom b ON bi.BOM_ID=b.ID "
                        + "WHERE b.PARENT_ITEM_CODE=? LIMIT 1", String.class, product.getItemCode());
        MoSeed.mkStock(jdbc, childCode, "9999");
        MrpMo mo = approvedTrialMo(product, stage, qty, "总结评审验证");
        List<com.erp.service.mrp.DispatchService.GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new com.erp.service.mrp.DispatchService.GenReq(
                        (String) r.get("moId"), ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        int i = 0;
        for (com.erp.entity.mrp.MrpDispatch d : dispatchService.generate(rows)) {
            dispatchService.confirm(d.getId());
            com.erp.entity.mrp.MrpReporting r = reportingService.open(d.getId(), null);
            // 设备 2h（setup/run 等标准不告警；machine 无标准仅记录）——归集 overhead 数据源
            reportingService.saveHours(r.getId(), new BigDecimal("0.5"), BigDecimal.ONE,
                    new BigDecimal("2"));
            if (i == 1) {
                reportingService.saveOutput(r.getId(), new BigDecimal(lastGood),
                        new BigDecimal(lastNg), null, false);
            } else {
                reportingService.saveOutput(r.getId(), new BigDecimal(qty), BigDecimal.ZERO,
                        null, false);
            }
            reportingService.confirm(r.getId());
            i++;
        }
        TrialData d10 = new TrialData();
        d10.setMoId(mo.getId());
        d10.setOpSeq(10);
        d10.setActualHours(new BigDecimal("2"));
        trialDataService.save(d10);
        TrialData d20 = new TrialData();
        d20.setMoId(mo.getId());
        d20.setOpSeq(20);
        d20.setActualHours(new BigDecimal("1.6"));
        trialDataService.save(d20);
        return mo;
    }

    @Test
    void summaryMetricsAndThreePartyReview() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        try {
            MrpMo mo = readyForSummary("总结评审产品");

            // ① 指标服务端计算：客户端伪造良率 100 / 达成 999 无效
            TrialSummary forged = new TrialSummary();
            forged.setMoId(mo.getId());
            forged.setYieldPct(new BigDecimal("100"));
            forged.setHoursAchievePct(new BigDecimal("999"));
            forged.setIssues("散热不良 2 台；建议散热片料号变更");
            TrialSummary s = trialSummaryService.save(forged);
            assertEquals("DRAFT", s.getStatus(), "建档 DRAFT");
            // 良率 = 9 ÷ 10 × 100 = 90（末道累计，客户端值被忽略）
            assertEquals(0, s.getYieldPct().compareTo(new BigDecimal("90")),
                    "良率服务端计算 90：" + s.getYieldPct());
            // 工时达成 = (1.5×2) ÷ 3.6 × 100 = 83.3333（标准 2×(0.5+1)=3.0、实测 3.6）
            assertEquals(0, s.getHoursAchievePct().compareTo(new BigDecimal("83.3333")),
                    "工时达成服务端计算 83.3333：" + s.getHoursAchievePct());
            assertEquals("散热不良 2 台；建议散热片料号变更", s.getIssues(), "问题清单保留");

            // ② 提交 → 三并行节点
            TrialSummary p = trialSummaryService.submit(s.getSummaryNo());
            assertEquals("PENDING", p.getStatus(), "提交 PENDING");
            List<Map<String, Object>> tasks = jdbc.queryForList(
                    "SELECT t.ROLE_REQUIRED FROM erp_sys_approval_task t JOIN erp_sys_approval a "
                            + "ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='TrialReview' AND a.BIZ_ID=?"
                            + " AND t.STATUS='ACTIVE'", s.getSummaryNo());
            assertEquals(3, tasks.size(), "单 SEQ 三并行节点：" + tasks);

            // ③ 单方驳回 → REJECTED 意见留痕（重提）
            String tq = activeTaskId("TrialReview", s.getSummaryNo(), "ROLE_QUALITY_MGR");
            MoTestAuth.login("q-mgr", "ROLE_QUALITY_MGR");
            approvalEngine.reject(tq, "不良模式未闭环，退回补充");
            MoTestAuth.login("trial-exec-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
            TrialSummary rejected = trialSummaryService.get(s.getSummaryNo());
            assertEquals("REJECTED", rejected.getStatus(), "单方驳回 REJECTED");
            assertEquals("不良模式未闭环，退回补充", rejected.getRejectReason(), "意见留痕");

            // ④ 重提后三方全过 → APPROVED
            trialSummaryService.submit(s.getSummaryNo());
            MoTestAuth.login("p-mgr", "ROLE_PROCESS_MGR");
            approvalEngine.pass(activeTaskId("TrialReview", s.getSummaryNo(), "ROLE_PROCESS_MGR"),
                    "工艺确认");
            MoTestAuth.login("q-mgr", "ROLE_QUALITY_MGR");
            approvalEngine.pass(activeTaskId("TrialReview", s.getSummaryNo(), "ROLE_QUALITY_MGR"),
                    "质量确认");
            MoTestAuth.login("plan-mgr", "ROLE_PLAN_MGR");
            approvalEngine.pass(activeTaskId("TrialReview", s.getSummaryNo(), "ROLE_PLAN_MGR"),
                    "计划确认");
            MoTestAuth.login("trial-exec-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
            TrialSummary approved = trialSummaryService.get(s.getSummaryNo());
            assertEquals("APPROVED", approved.getStatus(), "三方全过 APPROVED");
            assertNotNull(approveByOrNull(approved.getApproveBy()), "审批人留痕");
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        }
    }

    private String approveByOrNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    // ================= 3.8 阶段闸口与 PVT 良率闸口（D10） =================

    @Test
    void stageGateBlocksDvtUntilEvtReviewApproved() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        try {
            // 同产品 EVT 试产单 + 总结（评审未过）
            MrpMo ev = readyForSummary("阶段闸口产品");
            TrialSummary es = new TrialSummary();
            es.setMoId(ev.getId());
            TrialSummary summary = trialSummaryService.save(es);

            TrialApply dvt = new TrialApply();
            dvt.setTrialStage("DVT");
            dvt.setProductCode(ev.getProductCode());
            dvt.setQty(BigDecimal.TEN);
            dvt.setGoal("DVT 散热验证");

            // ① 总结 DRAFT → 建 DVT 申请 422（阶段闸口）
            var gate1 = assertThrows(ServiceException.class,
                    () -> trialApplyService.create(dvt));
            assertEquals(422, gate1.getCode(), "闸口 422：" + gate1.getMessage());
            assertTrue(gate1.getMessage().contains("阶段闸口"), gate1.getMessage());

            // ② 评审 PENDING → 仍 422
            trialSummaryService.submit(summary.getSummaryNo());
            var gate2 = assertThrows(ServiceException.class,
                    () -> trialApplyService.create(dvt));
            assertEquals(422, gate2.getCode(), "PENDING 仍阻断：" + gate2.getMessage());

            // ③ 三方全过 APPROVED → 放行
            MoTestAuth.login("p-mgr", "ROLE_PROCESS_MGR");
            approvalEngine.pass(activeTaskId("TrialReview", summary.getSummaryNo(), "ROLE_PROCESS_MGR"),
                    "工艺确认");
            MoTestAuth.login("q-mgr", "ROLE_QUALITY_MGR");
            approvalEngine.pass(activeTaskId("TrialReview", summary.getSummaryNo(), "ROLE_QUALITY_MGR"),
                    "质量确认");
            MoTestAuth.login("plan-mgr", "ROLE_PLAN_MGR");
            approvalEngine.pass(activeTaskId("TrialReview", summary.getSummaryNo(), "ROLE_PLAN_MGR"),
                    "计划确认");
            MoTestAuth.login("trial-exec-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
            TrialApply created = trialApplyService.create(dvt);
            assertEquals("DRAFT", created.getStatus(), "评审过后 DVT 申请建档成功");
            assertEquals("TA-", created.getTrialNo().substring(0, 3), "TA 编号");
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        }
    }

    @Test
    void pvtYieldGateBlocksBelowThreshold() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("TRIAL_PVT_YIELD_MIN", "95", "NUMBER", "MRP", "测试闸口", "test");
        try {
            // PVT 良率 91%（91÷100）< 95 → 提交 422 含「良率未达标」
            MrpMo low = readyForSummary("PVT低良率产品", "PVT", "100", "91", "9");
            TrialSummary ls = new TrialSummary();
            ls.setMoId(low.getId());
            TrialSummary lowSummary = trialSummaryService.save(ls);
            assertEquals(0, lowSummary.getYieldPct().compareTo(new BigDecimal("91")),
                    "良率 91：" + lowSummary.getYieldPct());
            var blocked = assertThrows(ServiceException.class,
                    () -> trialSummaryService.submit(lowSummary.getSummaryNo()));
            assertEquals(422, blocked.getCode(), "良率闸口 422：" + blocked.getMessage());
            assertTrue(blocked.getMessage().contains("良率未达标"), blocked.getMessage());
            assertEquals("DRAFT", trialSummaryService.get(lowSummary.getSummaryNo()).getStatus(),
                    "被闸口拦下不入 PENDING");

            // 达标 95% → 放行 PENDING
            MrpMo ok = readyForSummary("PVT达标产品", "PVT", "100", "95", "5");
            TrialSummary okSum = new TrialSummary();
            okSum.setMoId(ok.getId());
            TrialSummary passed = trialSummaryService.save(okSum);
            assertEquals(0, passed.getYieldPct().compareTo(new BigDecimal("95")),
                    "良率 95：" + passed.getYieldPct());
            assertEquals("PENDING", trialSummaryService.submit(passed.getSummaryNo()).getStatus(),
                    "达标放行入评审");
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("TRIAL_PVT_YIELD_MIN", "95", "NUMBER", "MRP", "恢复默认", "test");
        }
    }

    // ================= 3.10 转量产复制与发布阻断（FR-4.5-8-7 / D11） =================

    /** 三方会签通过当前试产总结（复用 3.7 模式；已 PENDING 则直接签） */
    private void approveSummary(String summaryNo) {
        if (!"PENDING".equals(trialSummaryService.get(summaryNo).getStatus())) {
            trialSummaryService.submit(summaryNo);
        }
        MoTestAuth.login("p-mgr", "ROLE_PROCESS_MGR");
        approvalEngine.pass(activeTaskId("TrialReview", summaryNo, "ROLE_PROCESS_MGR"), "工艺确认");
        MoTestAuth.login("q-mgr", "ROLE_QUALITY_MGR");
        approvalEngine.pass(activeTaskId("TrialReview", summaryNo, "ROLE_QUALITY_MGR"), "质量确认");
        MoTestAuth.login("plan-mgr", "ROLE_PLAN_MGR");
        approvalEngine.pass(activeTaskId("TrialReview", summaryNo, "ROLE_PLAN_MGR"), "计划确认");
        MoTestAuth.login("trial-exec-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    /** 试产版 BOM + 试产版路线（TRIAL 态）+ 转开释放的 TP- 工单（无报工——promote 只需评审） */
    private MrpMo trialMoWithTrialArtifacts(String name) {
        MdmItem product = productWithBom(name);
        // 试产版 BOM（草稿 → JDBC 置 TRIAL 态 + 阶段）
        String childCode = jdbc.queryForObject(
                "SELECT bi.ITEM_CODE FROM erp_mrp_bom_item bi JOIN erp_mrp_bom b ON bi.BOM_ID=b.ID "
                        + "WHERE b.PARENT_ITEM_CODE=? LIMIT 1", String.class, product.getItemCode());
        MrpBom head = new MrpBom();
        head.setParentItemCode(product.getItemCode());
        head.setTrialStage("EVT");
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(childCode);
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        MrpBom trialBom = bomService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_bom SET STATUS='TRIAL' WHERE ID=?", trialBom.getId());
        // 试产版路线（草稿 → 置 TRIAL 态）
        String base = opCode + "-" + product.getItemCode().substring(
                Math.max(0, product.getItemCode().length() - 6)) + "-t";
        List<com.erp.entity.mrp.MrpRoutingOp> rtRows = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String code = base + "-" + i;
            com.erp.entity.mrp.MrpOperation op = new com.erp.entity.mrp.MrpOperation();
            op.setOpCode(code);
            op.setOpName("试产转量产工序" + i);
            operationService.create(op);
            com.erp.entity.mrp.MrpOpWcStandard std = new com.erp.entity.mrp.MrpOpWcStandard();
            std.setOpCode(code);
            std.setWcCode(wcCode);
            std.setSetupHours(new BigDecimal("0.5"));
            std.setRunHours(BigDecimal.ONE);
            standardService.create(std);
            com.erp.entity.mrp.MrpRoutingOp rtRow = new com.erp.entity.mrp.MrpRoutingOp();
            rtRow.setOpCode(code);
            rtRow.setWcCode(wcCode);
            rtRow.setLeadTime(BigDecimal.ONE);
            rtRow.setStandardYield(new BigDecimal("98"));
            rtRow.setKeyOpFlag("0");
            rtRows.add(rtRow);
        }
        com.erp.entity.mrp.MrpRouting rtHead = new com.erp.entity.mrp.MrpRouting();
        rtHead.setItemCode(product.getItemCode());
        rtHead.setTrialStage("EVT");
        com.erp.entity.mrp.MrpRouting trialRt = routingService.create(rtHead, rtRows);
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='TRIAL' WHERE ID=?", trialRt.getId());
        return approvedTrialMo(product, "EVT", "10", "转量产验证");
    }

    @Test
    void promoteClonesProductionDraftsAndBlocksUnreviewedPublish() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        try {
            // ① 评审 PENDING 时 promote 422（spec「评审 PENDING 时 promote 422」）
            MrpMo mo = trialMoWithTrialArtifacts("转量产产品");
            TrialSummary sum = new TrialSummary();
            sum.setMoId(mo.getId());
            TrialSummary summary = trialSummaryService.save(sum);
            trialSummaryService.submit(summary.getSummaryNo());
            var pendingBlock = assertThrows(ServiceException.class,
                    () -> trialOrderService.promote(mo.getId()));
            assertEquals(422, pendingBlock.getCode(), "评审 PENDING 阻断 422：" + pendingBlock.getMessage());
            assertTrue(pendingBlock.getMessage().contains("评审未通过"), pendingBlock.getMessage());

            // 三方会签 APPROVED → promote 成功，两草稿版本号自动分配 + SRC_TRIAL_NO 正确
            approveSummary(summary.getSummaryNo());
            Map<String, Object> promoted = trialOrderService.promote(mo.getId());
            assertNotNull(promoted.get("bomId"), "BOM 草稿返回：" + promoted);
            assertNotNull(promoted.get("routingId"), "路线草稿返回：" + promoted);
            assertEquals(mo.getMoNo(), promoted.get("srcTrialNo"), "来源工单号");
            String bomId = (String) promoted.get("bomId");
            String routingId = (String) promoted.get("routingId");
            Map<String, Object> bom = jdbc.queryForMap(
                    "SELECT STATUS, TRIAL_STAGE, SRC_TRIAL_NO FROM erp_mrp_bom WHERE ID=?", bomId);
            assertEquals("DRAFT", bom.get("STATUS"), "量产草稿 DRAFT");
            assertEquals(null, bom.get("TRIAL_STAGE"), "量产草稿无试产阶段");
            assertEquals(mo.getMoNo(), bom.get("SRC_TRIAL_NO"), "SRC_TRIAL_NO 留痕");
            Map<String, Object> rt = jdbc.queryForMap(
                    "SELECT STATUS, TRIAL_STAGE, SRC_TRIAL_NO FROM erp_mrp_routing WHERE ID=?", routingId);
            assertEquals("DRAFT", rt.get("STATUS"), "路线量产草稿 DRAFT");
            assertEquals(mo.getMoNo(), rt.get("SRC_TRIAL_NO"), "路线 SRC_TRIAL_NO 留痕");
            // 原试产版零改动（仍 TRIAL 态）
            Integer trialStill = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_mrp_bom WHERE ID<>? AND TRIAL_STAGE IS NOT NULL "
                            + "AND STATUS='TRIAL' AND PARENT_ITEM_CODE=?",
                    Integer.class, bomId, mo.getProductCode());
            assertTrue(trialStill >= 1, "原试产版仍 TRIAL 态：" + trialStill);

            // ② 评审被回退（模拟驳回）→ 未评审草稿提交发布 422
            jdbc.update("UPDATE erp_mrp_trial_summary SET STATUS='REJECTED' WHERE MO_ID=?", mo.getId());
            var submitBlocked = assertThrows(ServiceException.class,
                    () -> bomService.submit(bomId));
            assertEquals(422, submitBlocked.getCode(), "未评审提交发布 422：" + submitBlocked.getMessage());
            assertTrue(submitBlocked.getMessage().contains("评审未通过"), submitBlocked.getMessage());
            var rtBlocked = assertThrows(ServiceException.class,
                    () -> routingService.submit(routingId));
            assertEquals(422, rtBlocked.getCode(), "路线未评审提交 422：" + rtBlocked.getMessage());

            // 评审恢复 APPROVED → 提交与签署放行（发布链回归）
            jdbc.update("UPDATE erp_mrp_trial_summary SET STATUS='APPROVED' WHERE MO_ID=?", mo.getId());
            bomService.submit(bomId);
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT STATUS FROM erp_mrp_bom WHERE ID=?", String.class, bomId),
                    "评审通过后提交放行");
            bomService.publishApproved(bomId);
            assertEquals("PUBLISHED", jdbc.queryForObject(
                    "SELECT STATUS FROM erp_mrp_bom WHERE ID=?", String.class, bomId),
                    "签署发布放行");

            // ③ 普通版本发布回归不受影响（无 SRC_TRIAL_NO 直通）
            MdmItem plain = mkMake("普通发布回归产品");
            MdmItem plainChild = mkMake("普通发布回归子件");
            MrpBom head = new MrpBom();
            head.setParentItemCode(plain.getItemCode());
            MrpBomItem row = new MrpBomItem();
            row.setItemCode(plainChild.getItemCode());
            row.setQty(BigDecimal.ONE);
            row.setLossRate(BigDecimal.ZERO);
            MrpBom draft = bomService.create(head, List.of(row));
            bomService.submit(draft.getId());
            bomService.publishApproved(draft.getId());
            assertEquals("PUBLISHED", jdbc.queryForObject(
                    "SELECT STATUS FROM erp_mrp_bom WHERE ID=?", String.class, draft.getId()),
                    "普通版本发布回归不受影响");
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        }
    }

    // ================= 3.11 成本归集回拨与关单预检（FR-4.5-8-8） =================

    @Test
    void closeGateBlocksOnReviewAndRemainderUntilAcked() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        try {
            MrpMo mo = readyForSummary("关单预检产品", "EVT", "10", "10", "0");
            Map<String, Object> done = reportingService.completeMo(mo.getId());
            assertEquals("COMPLETED", done.get("status"), "完工推进");

            // ① 评审 PENDING（仅 DRAFT 总结）→ 关单 422 评审未通过
            TrialSummary sum = new TrialSummary();
            sum.setMoId(mo.getId());
            TrialSummary summary = trialSummaryService.save(sum);
            var reviewBlocked = assertThrows(ServiceException.class,
                    () -> moService.close(mo.getId()));
            assertEquals(422, reviewBlocked.getCode(), "评审闸 422：" + reviewBlocked.getMessage());
            assertTrue(reviewBlocked.getMessage().contains("评审"), reviewBlocked.getMessage());

            // ② 评审 APPROVED → 无剩余料直接关单（成本归集已在完工执行）
            approveSummary(summary.getSummaryNo());
            // 先造剩余料：POSTED 领料 × TRIAL_FLAG=1 批次 × 可用余量
            String childCode = jdbc.queryForObject(
                    "SELECT bi.ITEM_CODE FROM erp_mrp_bom_item bi JOIN erp_mrp_bom b ON bi.BOM_ID=b.ID "
                            + "WHERE b.PARENT_ITEM_CODE=? LIMIT 1", String.class, mo.getProductCode());
            String issueId = "iss-close-" + (System.nanoTime() % 100000);
            jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE,"
                            + " WORK_ORDER_NO, STATUS, DEL_FLAG, VER_NO) VALUES (?,?, 'OWN', ?,"
                            + " 'POSTED','0',0)",
                    issueId, "MI-CLOSE-" + (System.nanoTime() % 100000), mo.getMoNo());
            jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO,"
                            + " ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, DEL_FLAG, VER_NO)"
                            + " VALUES (?,?,1,?,?,?,?, '0',0)",
                    issueId + "-L1", issueId, childCode, "关单剩余料子件", "TR-REM-1",
                    new BigDecimal("5"));
            jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME,"
                            + " TRIAL_FLAG, STATUS, CREATE_BY) VALUES (?,?,?,?, '1','1','junit')",
                    "bt-rem-1", "TR-REM-1", childCode, "关单剩余料子件");
            jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME,"
                            + " BATCH_NO, QTY, AVAILABLE_QTY, CREATE_BY) VALUES "
                            + "(?, 'WH-MAIN', ?, ?, 'TR-REM-1', 3, 3, 'junit')",
                    "st-rem-1", childCode, "关单剩余料子件");

            // ③ 剩余料明细可查（L2 提示源）
            List<Map<String, Object>> remainder = trialOrderService.remainderOf(mo.getId());
            assertEquals(1, remainder.size(), "剩余料明细 1 项：" + remainder);
            assertEquals("TR-REM-1", remainder.get(0).get("batchNo"), "批次明细");
            assertEquals(0, ((BigDecimal) remainder.get(0).get("availableQty"))
                    .compareTo(new BigDecimal("3")), "可用余量 3");

            // 未 ack → 关单 422 列明细
            var remainderBlocked = assertThrows(ServiceException.class,
                    () -> moService.close(mo.getId()));
            assertEquals(422, remainderBlocked.getCode(), "剩余料闸 422：" + remainderBlocked.getMessage());
            assertTrue(remainderBlocked.getMessage().contains("TR-REM-1"),
                    "明细列批次：" + remainderBlocked.getMessage());
            assertTrue(remainderBlocked.getMessage().contains("确认留痕"),
                    "提示确认留痕：" + remainderBlocked.getMessage());

            // ④ ack 留痕 → 关单成功（终态 + closeBy/closeAt 留痕）
            Map<String, Object> ack = trialOrderService.ackRemainder(mo.getId(), "余量 3 已退库");
            assertEquals(Boolean.TRUE, ack.get("acked"), "ack 成功");
            MrpMo closed = moService.close(mo.getId());
            assertEquals(MrpMo.ST_CLOSED, closed.getStatus(), "关单终态");
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT CLOSE_BY, CLOSE_AT FROM erp_mrp_mo WHERE ID=?", mo.getId());
            assertNotNull(row.get("CLOSE_BY"), "关闭人留痕");
            assertNotNull(row.get("CLOSE_AT"), "关闭时间留痕");
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        }
    }

    @Test
    void reversalRebuildsTrialCostWithoutDuplicates() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        try {
            MrpMo mo = readyForSummary("冲销回拨产品", "EVT", "10", "10", "0");
            reportingService.completeMo(mo.getId());

            // 完工归集三行（overhead = 4h × 100 = 400）
            Map<String, Object> before = trialOrderService.costOf(mo.getId());
            assertNotNull(before.get("collectedTotal"), "汇总可查");
            BigDecimal overheadBefore = costAmount(mo.getId(), "OVERHEAD");
            assertEquals(0, overheadBefore.compareTo(new BigDecimal("400")),
                    "冲销前 overhead 400：" + overheadBefore);

            // 冲销一道 CONFIRMED 报工 → TRIAL 分支重建归集
            String rptId = jdbc.queryForObject(
                    "SELECT ID FROM erp_mrp_reporting WHERE MO_ID=? AND STATUS='CONFIRMED'"
                            + " ORDER BY CREATE_DATE LIMIT 1", String.class, mo.getId());
            reportingService.applyReversal(rptId, "测试冲销触发回拨");
            String taskId = jdbc.queryForObject(
                    "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID"
                            + " WHERE a.BIZ_TYPE='ReportReversal' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                    String.class, rptId);
            MoTestAuth.login("plan-mgr", "ROLE_PLAN_MGR");
            approvalEngine.pass(taskId, "同意冲销");
            MoTestAuth.login("trial-exec-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");

            // 重建：overhead 回落（2h × 100 = 200），三类行各 1 无重复
            BigDecimal overheadAfter = costAmount(mo.getId(), "OVERHEAD");
            assertEquals(0, overheadAfter.compareTo(new BigDecimal("200")),
                    "冲销后 overhead 回落 200：" + overheadAfter);
            for (String type : new String[]{"MATERIAL", "LABOR", "OVERHEAD"}) {
                Long cnt = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM erp_mrp_trial_cost WHERE MO_ID=? AND COST_TYPE=?"
                                + " AND REMARK LIKE 'TRIAL:%' AND DEL_FLAG='0'",
                        Long.class, mo.getId(), type);
                assertEquals(1L, cnt, type + " 重建后仅 1 行（无重复）");
            }
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        }
    }

    /** 指定类型归集金额（逻辑删除表：须过滤 DEL_FLAG，否则读到重建前旧行——5.8 COPQ 同款教训） */
    private BigDecimal costAmount(String moId, String costType) {
        BigDecimal v = jdbc.query(
                "SELECT AMOUNT FROM erp_mrp_trial_cost WHERE MO_ID=? AND COST_TYPE=?"
                        + " AND REMARK LIKE 'TRIAL:%' AND DEL_FLAG='0'",
                (rs, i) -> rs.getBigDecimal(1), moId, costType).stream().findFirst().orElse(null);
        return v == null ? BigDecimal.ZERO : v;
    }

    // ================= 采集表单守卫 =================

    @Test
    void dataFormGuards() {
        MdmItem product = productWithBom("采集守卫产品");
        publishTwoOpRoute(product);
        MrpMo mo = approvedTrialMo(product, "EVT", "6", "采集守卫验证");

        // 不存在的工序 → 422
        var badOp = assertThrows(ServiceException.class,
                () -> trialDataService.form(mo.getId(), 99));
        assertEquals(422, badOp.getCode(), "工序不存在 422：" + badOp.getMessage());

        // 缺 moId/opSeq → 422
        var missing = assertThrows(ServiceException.class,
                () -> trialDataService.save(new TrialData()));
        assertEquals(422, missing.getCode(), "必填 422：" + missing.getMessage());
    }
}
