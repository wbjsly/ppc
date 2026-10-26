package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpOutputDao;
import com.erp.dao.mrp.MrpReportingDao;
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
 * 整单红字冲销库测（task 2.6）：原因必填 422（spec「原因必填」）、
 * 审批通过五步反向——投料回加/红字镜像负值/原单 REVERSED 留痕/累计回拨
 * （spec「冲销生成红字」）、驳回零变化、二次冲销 422 幂等。
 */
@SpringBootTest
class ReportingReversalDbTest {

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
    private MrpReportingDao reportingDao;
    @Autowired
    private MrpOutputDao outputDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MdmItem child;
    private MrpMo mo;
    private MrpDispatch dispatch;

    private static final LocalDate START = LocalDate.of(2028, 11, 6);
    private static final LocalDate END = LocalDate.of(2028, 12, 4);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("rev-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "冲销测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "冲销测试子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        mo = MoSeed.releaseMo(moService, product, "10", START, END);

        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='ReportReversal'");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID"
                + " WHERE a.BIZ_TYPE='ReportReversal'");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID"
                + " WHERE a.BIZ_TYPE='ReportReversal'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='ReportReversal'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='REVERSAL_RESULT'");
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

    /** 造一张带投料+产出+工时的 CONFIRMED 报工单 */
    private MrpReporting confirmedRpt() {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.addFeed(rpt.getId(), child.getItemCode(), "B-REV", new BigDecimal("20"));
        reportingService.saveOutput(rpt.getId(), new BigDecimal("8"), new BigDecimal("2"),
                "SCRAP", false);
        reportingService.saveHours(rpt.getId(), BigDecimal.ZERO, new BigDecimal("1"),
                new BigDecimal("0.5"));
        return reportingService.confirm(rpt.getId());
    }

    private String reversalTask(String rptId) {
        return approvalEngine.todo().stream()
                .filter(t -> "ReportReversal".equals(t.get("bizType"))
                        && rptId.equals(t.get("bizId")))
                .map(t -> String.valueOf(t.get("taskId")))
                .findFirst().orElse(null);
    }

    @Test
    void reasonRequiredAndNotConfirmedRejected() {
        MrpReporting rpt = confirmedRpt();
        // 原因必填 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.applyReversal(rpt.getId(), "  "));
        assertEquals(422, e.getCode(), "无原因 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("原因"), "提示原因必填：" + e.getMessage());
        // OPEN 单不可冲销
        MrpReporting open = reportingService.open(dispatch.getId(), null);
        ServiceException notConfirmed = assertThrows(ServiceException.class,
                () -> reportingService.applyReversal(open.getId(), "测试"));
        assertEquals(422, notConfirmed.getCode(), "OPEN 不可冲销 → 422");
    }

    @Test
    void approveExecutesFiveStepReversal() {
        BigDecimal availBefore = childAvail();
        MrpReporting rpt = confirmedRpt();
        BigDecimal availConfirmed = childAvail();
        assertEquals(0, availConfirmed.compareTo(availBefore.subtract(new BigDecimal("20"))),
                "确认态投料已扣 20");

        // 累计（冲销前）
        Map<String, Object> before = reportingService.confirmedTotals(mo.getId(), dispatch.getOpSeq());
        assertEquals(0, ((BigDecimal) before.get("goodQty")).compareTo(new BigDecimal("8")),
                "冲销前累计合格 8");

        // 申请（原因必填已过）→ 主管通过 → 五步反向
        reportingService.applyReversal(rpt.getId(), "录错工时，整单冲销");
        String taskId = reversalTask(rpt.getId());
        assertNotNull(taskId, "冲销待办已生成");
        approvalEngine.pass(taskId, "同意冲销");

        // ① 投料回加
        assertEquals(0, childAvail().compareTo(availBefore), "投料回加恢复原值");
        // ④ 原单 REVERSED + 关联镜像
        MrpReporting reversed = reportingService.get(rpt.getId());
        assertEquals(MrpReporting.ST_REVERSED, reversed.getStatus(), "原单置 REVERSED");
        assertNotNull(reversed.getReversedById(), "关联镜像单");
        assertNotNull(queryStr("SELECT REVERSE_BY FROM erp_mrp_reporting WHERE ID=?",
                rpt.getId()), "冲销人留痕");
        assertNotNull(queryStr("SELECT REVERSE_REASON FROM erp_mrp_reporting WHERE ID=?",
                rpt.getId()), "冲销原因留痕");
        // ② 红字镜像：负值产出
        MrpReporting mirror = reportingService.get(reversed.getReversedById());
        assertEquals(MrpReporting.ST_REVERSED, mirror.getStatus(), "镜像单状态");
        assertEquals(rpt.getId(), mirror.getReversesId(), "REVERSES_ID 指向原单");
        assertEquals(0, mirror.getRunHours().compareTo(new BigDecimal("-1")),
                "镜像工时为负（红字）");
        MrpOutput redOut = outputDao.selectOne(new LambdaQueryWrapper<MrpOutput>()
                .eq(MrpOutput::getRptId, mirror.getId()).last("LIMIT 1"));
        assertNotNull(redOut, "镜像产出行存在");
        assertEquals(0, redOut.getGoodQty().compareTo(new BigDecimal("-8")), "红字合格 -8");

        // ⑤ 累计回拨（REVERSED 剔除）
        Map<String, Object> after = reportingService.confirmedTotals(mo.getId(), dispatch.getOpSeq());
        assertEquals(0, ((BigDecimal) after.get("goodQty")).compareTo(BigDecimal.ZERO),
                "回拨后累计合格 0");
        assertEquals(0L, after.get("rptCount"), "回拨后确认单数 0");

        // 二次冲销 422（幂等）
        ServiceException again = assertThrows(ServiceException.class,
                () -> reportingService.applyReversal(rpt.getId()));
        assertEquals(422, again.getCode(), "二次冲销 → 422：" + again.getMessage());
    }

    @Test
    void rejectKeepsConfirmed() {
        MrpReporting rpt = confirmedRpt();
        BigDecimal avail = childAvail();
        reportingService.applyReversal(rpt.getId(), "误申请");
        String taskId = reversalTask(rpt.getId());
        assertNotNull(taskId, "待办存在");
        approvalEngine.reject(taskId, "数据无误，不冲");
        // 驳回零变化
        MrpReporting kept = reportingService.get(rpt.getId());
        assertEquals(MrpReporting.ST_CONFIRMED, kept.getStatus(), "驳回后仍 CONFIRMED");
        assertEquals(null, kept.getReversedById(), "无镜像");
        assertEquals(0, childAvail().compareTo(avail), "库存未动");
        // 仍可正常提交流程（未被锁）——confirm 已是终态，仅验证状态可读
        assertNotNull(kept.getConfirmAt(), "确认留痕保留");
    }

    private BigDecimal childAvail() {
        BigDecimal v = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=?",
                BigDecimal.class, child.getItemCode());
        return v == null ? BigDecimal.ZERO : v;
    }

    private String queryStr(String sql, Object... args) {
        Object v = jdbc.queryForObject(sql, String.class, args);
        return v == null ? null : v.toString();
    }
}
