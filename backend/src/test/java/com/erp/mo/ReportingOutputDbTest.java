package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpOutputDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpOutput;
import com.erp.entity.mrp.MrpReporting;
import com.erp.service.approval.ApprovalEngine;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双模式产出与超产分级库测（task 2.4）：逐件/批量同结构落库等价（spec「逐件累加与批量等价」）、
 * 阈值内二次确认入账（spec「阈值内二次确认」）、超阈值挂 OverProd 审批→通过→可提交
 * （spec「超阈值挂起审批」）、驳回不计入且改量重提解除挂起。
 */
@SpringBootTest
class ReportingOutputDbTest {

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
    private ApprovalEngine approvalEngine;
    @Autowired
    private MrpOutputDao outputDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MdmItem child;
    private MrpMo mo;
    private MrpDispatch dispatch;

    /** 计划数量 100——整数件级超产的百分比分辨率（1 件 = 1% ≤ 容差 5%） */
    private static final String PLAN_QTY = "100";
    private static final LocalDate START = LocalDate.of(2028, 10, 9);
    private static final LocalDate END = LocalDate.of(2028, 10, 30);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("out-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "产出测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "产出测试子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        mo = MoSeed.releaseMo(moService, product, PLAN_QTY, START, END);

        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='OverProd' AND APPLY_BY='out-tester'");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID"
                + " WHERE a.BIZ_TYPE='OverProd'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='OverProd'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='OVERPROD_RESULT'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER'");
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

    /** 找到本单 OverProd 审批待办任务 id */
    private String overProdTask(String rptId) {
        return approvalEngine.todo().stream()
                .filter(t -> "OverProd".equals(t.get("bizType"))
                        && rptId.equals(t.get("bizId")))
                .map(t -> String.valueOf(t.get("taskId")))
                .findFirst().orElse(null);
    }

    @Test
    void pieceAndBatchSameStructure() {
        // 逐件模式报工单：前端累加到 60 后一次提交（同结构落库）
        MrpReporting piece = reportingService.open(dispatch.getId(), MrpReporting.MODE_PIECE);
        MrpOutput po = reportingService.saveOutput(piece.getId(),
                new BigDecimal("58"), new BigDecimal("2"), "SCRAP", false);
        assertEquals(0, po.getGoodQty().compareTo(new BigDecimal("58")), "逐件累计合格落库");
        assertEquals(0, po.getNgQty().compareTo(new BigDecimal("2")), "逐件累计不合格落库");
        assertEquals("SCRAP", po.getNgDispose(), "处置方式落库");

        // 批量模式报工单：直接填同值 → 与逐件完全同结构
        MrpReporting batch = reportingService.open(dispatch.getId(), MrpReporting.MODE_BATCH);
        MrpOutput bo = reportingService.saveOutput(batch.getId(),
                new BigDecimal("58"), new BigDecimal("2"), "SCRAP", false);
        assertEquals(0, po.getGoodQty().compareTo(bo.getGoodQty()), "逐件与批量同值等价");
        assertEquals(0, po.getNgQty().compareTo(bo.getNgQty()), "不合格同值等价");
        assertEquals(po.getOpSeq(), bo.getOpSeq(), "同工序");
        // 覆盖式：再次提交更新同一行而非追加
        reportingService.saveOutput(batch.getId(), new BigDecimal("59"),
                new BigDecimal("1"), "SCRAP", false);
        assertEquals(1, outputDao.selectCount(new LambdaQueryWrapper<MrpOutput>()
                .eq(MrpOutput::getRptId, batch.getId())), "一单一产出行（覆盖式）");
    }

    @Test
    void overprodWithinToleranceNeedsAck() {
        // 累计 101 > 计划 100：超 1 件 = 1% ≤ 容差 5% → 未 ack 422 提示，ack 后入账
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.saveOutput(rpt.getId(),
                        new BigDecimal("101"), BigDecimal.ZERO, null, false));
        assertEquals(422, e.getCode(), "阈值内未确认 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("二次确认"), "提示二次确认：" + e.getMessage());

        MrpOutput out = reportingService.saveOutput(rpt.getId(),
                new BigDecimal("101"), BigDecimal.ZERO, null, true);
        assertEquals(0, out.getGoodQty().compareTo(new BigDecimal("101")), "ack 后入账");
        // 阈值内不挂审批
        assertEquals(null, overProdTask(rpt.getId()), "阈值内无 OverProd 待办");
    }

    @Test
    void overprodBeyondToleranceApproveThenConfirm() {
        // 累计 120 > 100：超 20% > 容差 5% → 保存成功但挂起（flag=1 + 提交 OverProd 审批），
        // 阻断点在 confirm（spec「超阈值挂起审批：报工单挂起，通过后方计入累计」）
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        MrpOutput out = reportingService.saveOutput(rpt.getId(),
                new BigDecimal("120"), BigDecimal.ZERO, null, true);
        assertEquals(0, out.getGoodQty().compareTo(new BigDecimal("120")), "挂起中产出仍落库");
        assertEquals("1", jdbc.queryForObject(
                "SELECT OVERPROD_FLAG FROM erp_mrp_reporting WHERE ID=?", String.class, rpt.getId()),
                "OVERPROD_FLAG=1");

        // 未过审批 confirm 阻断
        ServiceException blocked = assertThrows(ServiceException.class,
                () -> reportingService.confirm(rpt.getId()));
        assertEquals(422, blocked.getCode(), "挂起中确认 → 422：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("审批"), "提示待审批：" + blocked.getMessage());

        // 计划主管通过 → PROD_CONFIRM=1 → 确认成功
        String taskId = overProdTask(rpt.getId());
        assertNotNull(taskId, "OverProd 待办已生成");
        approvalEngine.pass(taskId, "同意超产");
        assertEquals("1", jdbc.queryForObject(
                "SELECT PROD_CONFIRM_FLAG FROM erp_mrp_reporting WHERE ID=?", String.class, rpt.getId()),
                "审批通过置 PROD_CONFIRM_FLAG");
        MrpReporting confirmed = reportingService.confirm(rpt.getId());
        assertEquals(MrpReporting.ST_CONFIRMED, confirmed.getStatus(), "通过后确认成功");

        // 累计已计入（confirmedTotals 反映 120）
        Map<String, Object> totals = reportingService.confirmedTotals(mo.getId(), dispatch.getOpSeq());
        assertEquals(0, ((BigDecimal) totals.get("goodQty")).compareTo(new BigDecimal("120")),
                "超产量计入累计（approved 后）");
    }

    @Test
    void overprodRejectKeepsBlockedAndReAckReleases() {
        // 挂起（保存成功）→ 驳回 → confirm 仍阻断
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(),
                new BigDecimal("130"), BigDecimal.ZERO, null, true);
        assertEquals("1", jdbc.queryForObject(
                "SELECT OVERPROD_FLAG FROM erp_mrp_reporting WHERE ID=?", String.class, rpt.getId()),
                "超阈值保存后挂起");
        String taskId = overProdTask(rpt.getId());
        assertNotNull(taskId, "待办存在");
        approvalEngine.reject(taskId, "超产过多，核减后重提");
        ServiceException still = assertThrows(ServiceException.class,
                () -> reportingService.confirm(rpt.getId()));
        assertEquals(422, still.getCode(), "驳回后仍阻断：" + still.getMessage());

        // 改量重提：累计 95 ≤ 100 → 无超产 → 挂起解除 → 确认放行
        MrpOutput out = reportingService.saveOutput(rpt.getId(),
                new BigDecimal("95"), BigDecimal.ZERO, null, false);
        assertEquals(0, out.getGoodQty().compareTo(new BigDecimal("95")), "改量落库");
        assertEquals("0", jdbc.queryForObject(
                "SELECT OVERPROD_FLAG FROM erp_mrp_reporting WHERE ID=?", String.class, rpt.getId()),
                "改量后挂起解除");
        MrpReporting confirmed = reportingService.confirm(rpt.getId());
        assertEquals(MrpReporting.ST_CONFIRMED, confirmed.getStatus(), "解除后确认成功");
    }
}
