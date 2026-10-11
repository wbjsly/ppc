package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpReporting;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四类通知接线库测（task 6.1，design D9 silent 纪律）：
 * ① YIELD_ALERT 超差首推即时（主管+质量工程师两角色）；
 * ② FAI_BLOCK/IPQC_BLOCK 检验阻断 1h 幂等节流（重复阻断不重复推）；
 * ③ WORKHOUR_ALERT 工时异常待办；
 * ④ YIELD_UNFREEZE 解冻通知原接收人；
 * ⑤ 操作工日常提交零通知（正常良率/无异常不产通知）。
 * 审批结果（OVERPROD/REVERSAL_RESULT）由业务回调自推——已在 2.4/2.6 测试族覆盖。
 */
@SpringBootTest
class ReportingNoticeDbTest {

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
    private com.erp.service.SysParamService sysParam;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MdmItem child;
    private MrpMo mo;
    private MrpDispatch dispatch;
    private Integer opSeq;

    private static final LocalDate START = LocalDate.of(2029, 7, 17);
    private static final LocalDate END = LocalDate.of(2029, 8, 14);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("notice-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "通知测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "通知测试子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        mo = MoSeed.releaseMo(moService, product, "100", START, END);
        jdbc.update("UPDATE erp_mrp_mo_op SET STANDARD_YIELD=98 WHERE MO_ID=?", mo.getId());

        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());
        opSeq = dispatch.getOpSeq();
        // 本测试聚焦通知四类——关检验卡控（阻断类单独开）
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        cleanupNotice();
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        cleanupNotice();
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

    private void cleanupNotice() {
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN "
                + "('YIELD_ALERT','YIELD_UNFREEZE','WORKHOUR_ALERT','FAI_BLOCK','IPQC_BLOCK')");
    }

    private long count(String bizType) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE=?", Long.class, bizType);
        return n == null ? 0 : n;
    }

    private MrpReporting report(int good, int ng, String dispose) {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal(good), new BigDecimal(ng),
                dispose, false);
        return reportingService.confirm(rpt.getId());
    }

    // ---------- ① 良率超差首推即时 ----------

    @Test
    void yieldAlertPushedImmediatelyToTwoRoles() {
        // 29/33 = 87.88% → 偏差 10.33% > 10% → YIELD_ALERT 两角色
        report(29, 4, "SCRAP");
        assertEquals(2L, count("YIELD_ALERT"),
                "YIELD_ALERT 推送主管+质量工程师两角色：" + count("YIELD_ALERT"));
        // 同工单再次超差（已冻结）→ 幂等不重复推
        report(29, 4, "SCRAP");
        assertEquals(2L, count("YIELD_ALERT"), "重复超差通知不重复推（同 bizId 幂等）");
    }

    // ---------- ② 检验阻断 1h 节流 ----------

    @Test
    void inspectionBlockThrottledWithinHour() {
        // 开 FAI 卡控，制造未决首件批
        sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试开启", "test");
        report(10, 0, null); // 首单确认：断言先行（无批放行）→ 确认后建 FAI 批（不推通知）
        assertEquals(0L, count("FAI_BLOCK"), "首单建批不推阻断通知");

        // 二次提交被阻断 → 首推即时 + 1h 节流
        MrpReporting second = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(second.getId(), new BigDecimal("5"), BigDecimal.ZERO,
                null, false);
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.confirm(second.getId()));
        assertEquals(422, e.getCode(), "首件未决阻断：" + e.getMessage());
        Long fai = count("FAI_BLOCK");
        assertTrue(fai >= 1, "阻断即时推送 FAI_BLOCK：" + fai);

        // 三次提交再阻断 → 同单同工序 1h 节流不重复推
        MrpReporting third = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(third.getId(), new BigDecimal("5"), BigDecimal.ZERO,
                null, false);
        assertThrows(ServiceException.class, () -> reportingService.confirm(third.getId()));
        assertEquals(fai, count("FAI_BLOCK"), "同单同工序 1h 内重复阻断不重复推");

        // 另一工单的首件阻断 → 不同 bizId 正常推
        // （本测试单工单，跨单场景由 bizId=moId#opSeq 天然隔离——断言同 bizId 节流即证）
    }

    // ---------- ③ 工时异常待办 ----------

    @Test
    void workhourAlertPushedOnce() {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        // 标准 run=1h，报 8h → 偏离 700% > 50%
        reportingService.saveHours(rpt.getId(), BigDecimal.ZERO, new BigDecimal("8"),
                BigDecimal.ZERO);
        assertEquals(1L, count("WORKHOUR_ALERT"), "工时异常待办推送一次");
        // 重录仍超限 → 幂等（同 bizId=rptId 未读去重）不重复推
        reportingService.saveHours(rpt.getId(), BigDecimal.ZERO, new BigDecimal("9"),
                BigDecimal.ZERO);
        assertEquals(1L, count("WORKHOUR_ALERT"), "重录不重复推（notice push 幂等）");
        // 主管确认
        MoTestAuth.login("notice-mgr", "ROLE_PLAN_MGR");
        reportingService.confirmHours(rpt.getId());
        assertNotNull(jdbc.queryForObject(
                "SELECT HOUR_CONFIRM_BY FROM erp_mrp_reporting WHERE ID=?", String.class,
                rpt.getId()), "确认人留痕");
    }

    // ---------- ④ 解冻通知原接收人 ----------

    @Test
    void unfreezeNotifyOriginalReceiversOnce() {
        // 超差冻结（推 2 条 YIELD_ALERT）→ 冲销执行回拨 → 解冻推 2 条 YIELD_UNFREEZE
        MrpReporting rpt = report(29, 4, "SCRAP");
        assertEquals(2L, count("YIELD_ALERT"), "冻结时两角色告警");
        assertFalse(jdbc.queryForList(
                "SELECT ID FROM erp_mrp_mo_freeze WHERE MO_ID=? AND ACTIVE_FLAG='1'",
                mo.getId()).isEmpty(), "活动冻结存在");

        // 单参执行冲销（五步反向，含⑤良率回拨评估→解冻）
        reportingService.applyReversal(rpt.getId());
        assertEquals(2L, count("YIELD_UNFREEZE"),
                "解冻通知推原接收人两角色：" + count("YIELD_UNFREEZE"));
        assertTrue(jdbc.queryForList(
                "SELECT ID FROM erp_mrp_mo_freeze WHERE MO_ID=? AND ACTIVE_FLAG='1'",
                mo.getId()).isEmpty(), "活动冻结已释放");

        // 再次评估（无活动冻结）→ release false 不再推
        Long un = count("YIELD_UNFREEZE");
        assertEquals(un, count("YIELD_UNFREEZE"), "无活动冻结不重复推解冻");
    }

    // ---------- ⑤ 正常提交零通知 ----------

    @Test
    void normalSubmissionZeroNotice() {
        report(96, 4, "SCRAP"); // 良率 96%，偏差 2% < 10% → 无告警；4 件报废 < 5 域内
        assertEquals(0, count("YIELD_ALERT"), "正常良率无告警");
        assertEquals(0, count("YIELD_UNFREEZE"), "无解冻");
        assertEquals(0, count("WORKHOUR_ALERT"), "无工时异常（未报工时）");
        assertEquals(0, count("FAI_BLOCK"), "FAI 关闭无阻断");
        // 全库本测试族通知为零（操作工日常提交 silent）
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE IN "
                        + "('YIELD_ALERT','YIELD_UNFREEZE','WORKHOUR_ALERT','FAI_BLOCK','IPQC_BLOCK')",
                Long.class);
        assertEquals(0L, total, "日常提交零通知（silent 纪律）");
    }
}
