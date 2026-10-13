package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpReporting;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.ReportingService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完工接管与倒冲库测（task 4.1 + 4.2 + 4.3，spec completion-backflush 九场景 + work-order MODIFIED 关闭钩子）：
 * 4.1 三校验逐一阻断（未完成工序列出/IPQC 未决/冻结）+ 通过推进 Completed（合格=末道累计）；
 * 4.2 倒冲差额防御（已足额零重复扣 100−60−30=10 补投、缺料 422 列明细、损耗率计入）；
 * 4.3 冲销完工回退（末道冲销回退 RELEASED+BFLUSH 回加、中间单保持 Completed）；
 * 4.4 关闭钩子（Completed 但有未完成工序关闭 422 列清单、全 DONE 关闭通过）。
 */
@SpringBootTest
class ReportingCompletionDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
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
    private DispatchService dispatchService;
    @Autowired
    private ReportingService reportingService;
    @Autowired
    private MrpMoOpDao moOpDao;
    @Autowired
    private com.erp.service.SysParamService sysParam;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MdmItem child;
    private MrpMo mo;
    private MrpDispatch d1;
    private MrpDispatch d2;
    private Integer op1;
    private Integer op2;

    private static final LocalDate START = LocalDate.of(2029, 6, 22);
    private static final LocalDate END = LocalDate.of(2029, 7, 20);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("cmp-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER",
                "ROLE_PLAN_MGR", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "完工测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "完工测试子件", "BUY");
        // C-4.5-09：关单前归集须完整——投料子件须有单价（标准成本或采购价兜底），
        // 否则完工自动归集 422 无 summary，关闭被成本检查阻断（依赖残留采购价曾致非确定性失败）
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 8 WHERE ITEM_CODE = ?",
                child.getItemCode());
        jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST = 100 WHERE ITEM_CODE = ?",
                product.getItemCode());
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0.1");
        MoSeed.publishRoutingMulti(routingService, operationService,
                workCenterService, standardService, jdbc, product, 2);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        mo = MoSeed.releaseMo(moService, product, "10", START, END);

        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        List<MrpDispatch> created = dispatchService.generate(rows);
        assertEquals(2, created.size(), "两工序派工");
        d1 = dispatchService.confirm(created.stream()
                .filter(d -> created.indexOf(d) == 0).findFirst().orElseThrow().getId());
        d2 = dispatchService.confirm(created.stream()
                .filter(d -> created.indexOf(d) == 1).findFirst().orElseThrow().getId());
        List<MrpMoOp> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId()).orderByAsc(MrpMoOp::getOpSeq));
        op1 = ops.get(0).getOpSeq();
        op2 = ops.get(1).getOpSeq();
        // 关首件/关键工序卡控（本测试聚焦完工/倒冲链；检验链在 3.6/3.7 已测）
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID LIKE 'iss-cmp-test%'");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_NO='MI-CMP-TEST'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER'");
        jdbc.update("DELETE FROM erp_mrp_mo_freeze");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER'");
        jdbc.update("DELETE FROM erp_mrp_output");
        jdbc.update("DELETE FROM erp_mrp_feed");
        jdbc.update("DELETE FROM erp_mrp_reporting");
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC-%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP-%'");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        MoSeed.cleanup(jdbc);
    }

    /** 两工序各报一单（good 默认 8）并确认 → 全 DONE */
    private void reportBothOps(int good1, int good2) {
        MrpReporting r1 = reportingService.open(dispatchOf(op1).getId(), null);
        reportingService.saveOutput(r1.getId(), new BigDecimal(good1), BigDecimal.ZERO,
                null, false);
        reportingService.confirm(r1.getId());
        MrpReporting r2 = reportingService.open(dispatchOf(op2).getId(), null);
        reportingService.saveOutput(r2.getId(), new BigDecimal(good2), BigDecimal.ZERO,
                null, false);
        reportingService.confirm(r2.getId());
    }

    private MrpDispatch dispatchOf(Integer opSeq) {
        return dispatchService.list(null, null).stream()
                .filter(d -> mo.getId().equals(d.getMoId()) && opSeq.equals(d.getOpSeq()))
                .findFirst().orElseThrow();
    }

    private BigDecimal childAvail() {
        BigDecimal v = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=?",
                BigDecimal.class, child.getItemCode());
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---------- 4.1 ----------

    @Test
    void completeBlockedByPendingItemsThenSucceeds() {
        // ① 仅报一道工序 → 未完成工序阻断（列出清单）
        MrpReporting r1 = reportingService.open(dispatchOf(op1).getId(), null);
        reportingService.saveOutput(r1.getId(), new BigDecimal("8"), BigDecimal.ZERO,
                null, false);
        reportingService.confirm(r1.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.completeMo(mo.getId()));
        assertEquals(422, e.getCode(), "有未完成工序 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("未完成") || e.getMessage().contains("完工校验"),
                "列出未决项：" + e.getMessage());

        // ③ 冻结阻断（手造活动冻结行）
        // REASON=NCR（YIELD 原因会被 r2 confirm 的良率回拨评估自动解掉——回拨只解 YIELD）
        jdbc.update("INSERT INTO erp_mrp_mo_freeze (ID, MO_ID, SCOPE, REASON, ACTIVE_FLAG,"
                        + " FROZEN_BY, FROZEN_AT, DEL_FLAG, VER_NO) VALUES (?,?,?,?,?,?,?,?,0)",
                "fz-cmp-test", mo.getId(), "MO", "NCR", "1", "TEST", LocalDateTime.now(), "0");
        // 第二道也报完，但冻结在
        MrpReporting r2 = reportingService.open(dispatchOf(op2).getId(), null);
        reportingService.saveOutput(r2.getId(), new BigDecimal("8"), BigDecimal.ZERO,
                null, false);
        reportingService.confirm(r2.getId());
        ServiceException fe = assertThrows(ServiceException.class,
                () -> reportingService.completeMo(mo.getId()));
        assertEquals(422, fe.getCode(), "冻结中完工 → 422：" + fe.getMessage());
        jdbc.update("DELETE FROM erp_mrp_mo_freeze WHERE ID='fz-cmp-test'");

        // 通过推进：合格 = 末道累计 8
        BigDecimal availBefore = childAvail();
        Map<String, Object> out = reportingService.completeMo(mo.getId());
        assertEquals("COMPLETED", out.get("status"), "推进 Completed");
        assertEquals(0, ((BigDecimal) out.get("qualifiedQty")).compareTo(new BigDecimal("8")),
                "合格 = 末道累计：" + out.get("qualifiedQty"));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, mo.getId()),
                "工单 COMPLETED");
        // 倒冲：应投 = 10 × 1 × 1.1 = 11；已投 = 两单各 0（本测试没投料！）→ 差额 11×2 行补投
        // BOM 仅子件一行 → 应投 11、投料 0、领料 0 → 补投 11
        BigDecimal after = childAvail();
        BigDecimal bflush = jdbc.queryForObject(
                "SELECT IFNULL(SUM(QTY),0) FROM erp_mrp_feed WHERE MO_ID=? AND FLOW_TYPE='BFLUSH'",
                BigDecimal.class, mo.getId());
        assertEquals(0, after.compareTo(availBefore.subtract(new BigDecimal("11"))),
                "倒冲补投 11（10×1×1.1，损耗率计入）：" + after);
        assertEquals(0, bflush.compareTo(new BigDecimal("11")), "BFLUSH 行合计 11");
        assertNotNull(out.get("backflush"), "倒冲明细返回");
    }

    // ---------- 4.2 ----------

    @Test
    void backflushDeficitDefenseAndShortage() {
        // QTY=100 → 应投 100×1×1.1=110；手工投料 80 + 领料过账 30 = 110 → 差额 0 零重复扣
        reportBothOps(8, 8);
        // 手工投料 60（两单各 30）
        jdbc.update("UPDATE erp_mrp_mo SET QTY=100 WHERE ID=?", mo.getId());
        MrpReporting f1 = reportingService.open(dispatchOf(op1).getId(), null);
        reportingService.addFeed(f1.getId(), child.getItemCode(), null, new BigDecimal("80"));
        // 模拟 POSTED 领料 30（直插头+行）
        String issueId = "iss-cmp-test";
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO,"
                        + " STATUS, DEL_FLAG, VER_NO) VALUES (?,?,?,?, 'POSTED','0',0)",
                issueId, "MI-CMP-TEST", "OWN", mo.getMoNo());
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE,"
                        + " QTY, DEL_FLAG, VER_NO) VALUES (?,?,1,?,?, '0',0)",
                issueId + "-L1", issueId, child.getItemCode(), new BigDecimal("30"));

        BigDecimal availBefore = childAvail();
        Map<String, Object> out = reportingService.completeMo(mo.getId());
        // 应投 110 − 投料 80 − 领料 30 = 0 → 零补投
        assertEquals(0, ((Number) out.get("backflushRows")).intValue(), "已足额零重复扣");
        assertEquals(0, childAvail().compareTo(availBefore), "库存未动");
    }

    @Test
    void backflushShortageBlocksCompletion() {
        reportBothOps(8, 8);
        jdbc.update("UPDATE erp_mrp_mo SET QTY=100 WHERE ID=?", mo.getId());
        // 把子件可用压到 5（应投 110）→ 缺料阻断
        jdbc.update("UPDATE erp_inv_stock SET AVAILABLE_QTY=5, QTY=5 WHERE ITEM_CODE=?",
                child.getItemCode());
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.completeMo(mo.getId()));
        assertEquals(422, e.getCode(), "缺料阻断完工 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("倒冲"), "提示倒冲缺料：" + e.getMessage());
        assertTrue(e.getMessage().contains(child.getItemCode()), "列出物料：" + e.getMessage());
        // 未完工
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, mo.getId()),
                "阻断后仍 RELEASED");
    }

    // ---------- 4.3 ----------

    @Test
    void reversalAfterCompletionRollsBackWhenLastOpUndone() {
        reportBothOps(8, 8);
        BigDecimal availBefore = childAvail();
        reportingService.completeMo(mo.getId());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, mo.getId()), "已完工");
        BigDecimal availCompleted = childAvail();
        assertTrue(availCompleted.compareTo(availBefore) < 0, "倒冲已扣料");

        // 冲销末道工序的唯一报工单 → op2 回退 + 工单回退 + BFLUSH 回加
        String last = jdbc.queryForObject(
                "SELECT ID FROM erp_mrp_reporting WHERE MO_ID=? AND OP_SEQ=? AND STATUS='CONFIRMED'",
                String.class, mo.getId(), op2);
        reportingService.applyReversal(last); // 单参=执行五步反向（审批链在 2.6 已测）
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID=? AND OP_SEQ=?",
                String.class, mo.getId(), op2), "末道工序回退 RUNNING");
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, mo.getId()),
                "工单回退 RELEASED（spec「完工状态回退」）");
        assertEquals(0, childAvail().compareTo(availBefore), "BFLUSH 回加恢复原值");
    }

    @Test
    void reversalOfMiddleOpKeepsCompletedWhenBaselineHeld() {
        // 中间单冲销：op1 仍有另一张 CONFIRMED → 全 DONE；但 good 下降致末道基线判定
        // 本场景：冲销 op1 的报工单但保留另一张（造两单）→ op1 仍 DONE、末道 good 不变 → 保持
        MrpReporting a1 = reportingService.open(dispatchOf(op1).getId(), null);
        reportingService.saveOutput(a1.getId(), new BigDecimal("5"), BigDecimal.ZERO, null, false);
        reportingService.confirm(a1.getId());
        MrpReporting a2 = reportingService.open(dispatchOf(op1).getId(), null);
        reportingService.saveOutput(a2.getId(), new BigDecimal("3"), BigDecimal.ZERO, null, false);
        reportingService.confirm(a2.getId());
        MrpReporting b = reportingService.open(dispatchOf(op2).getId(), null);
        reportingService.saveOutput(b.getId(), new BigDecimal("8"), BigDecimal.ZERO, null, false);
        reportingService.confirm(b.getId());

        reportingService.completeMo(mo.getId());
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, mo.getId()), "已完工");

        // 冲销 op1 的第一张（该工序仍剩 a2 CONFIRMED）→ 保持 Completed、累计回拨
        reportingService.applyReversal(a1.getId()); // 单参=执行五步反向
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, mo.getId()),
                "全工序仍 DONE 且末道基线不变 → 保持 Completed（spec 场景）");
        assertEquals("DONE", jdbc.queryForObject(
                "SELECT OP_STATUS FROM erp_mrp_mo_op WHERE MO_ID=? AND OP_SEQ=?",
                String.class, mo.getId(), op1), "op1 仍有确认单 → 保持 DONE");
    }

    // ---------- 4.4 ----------

    @Test
    void closeBlockedByUnfinishedOpAndPassesWhenAllDone() {
        // 完工但制造「未完成工序」（直改 op 状态模拟）→ 关闭 422 列清单
        // C-4.5-09：关单前归集须完整——预置当期制造费用池（否则 NO_POOL 标记按规格阻断）
        jdbc.update("INSERT IGNORE INTO erp_mrp_overhead_pool "
                + "(ID, PERIOD, POOL_AMT, RULE, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('pl-rptcomp', ?, 1000, 'TIME', 'junit', '0', 0)",
                java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
        reportBothOps(8, 8);
        reportingService.completeMo(mo.getId());
        // C-4.5-09 落地后完工自动归集：本测试业务是工序关单（差异审阅由 CostAggregationDbTest 覆盖），
        // 临时放宽差异阈值避免 89% 差异阻断；finally 必复位防泄漏影响后续按阈值断言的测试
        sysParam.setValue("COST_VARIANCE_RATE", "10", "NUMBER", "MRP", "工序关单测试放宽", "junit");
        try {
        jdbc.update("UPDATE erp_mrp_mo_op SET OP_STATUS='RUNNING' WHERE MO_ID=? AND OP_SEQ=?",
                mo.getId(), op1);
        ServiceException e = assertThrows(ServiceException.class,
                () -> moService.close(mo.getId()));
        assertEquals(422, e.getCode(), "未完成工序关闭 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("未完成工序"), "提示未完成工序：" + e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(op1)), "列出工序号：" + e.getMessage());

        // 恢复 DONE → 关闭通过（work-order MODIFIED「全 DONE 关闭通过」）
        jdbc.update("UPDATE erp_mrp_mo_op SET OP_STATUS='DONE' WHERE MO_ID=? AND OP_SEQ=?",
                mo.getId(), op1);
        Map<String, Object> pre = moService.precheckClose(mo.getId());
        assertEquals(Boolean.TRUE, pre.get("passed"), "预检通过：" + pre.get("checks"));
        MrpMo closed = moService.close(mo.getId());
        assertEquals("CLOSED", closed.getStatus(), "关闭终态");
        } finally {
            sysParam.setValue("COST_VARIANCE_RATE", "0.05", "NUMBER", "MRP", "测试复位", "junit");
        }
    }
}
