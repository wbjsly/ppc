package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpReportingDao;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报工主干库测（task 2.1 + 2.2）：
 * 2.1 状态机——OPEN 创建（单号 RP+日期+流水、派工快照带入、报告人/派工确认人留痕）、
 *    确认 CAS（重复确认 409）、确认后只读（requireOpen/discard 422）、放弃草稿、
 *    同工序多单累计（confirmedTotals 口径 = design D2）；
 * 2.2 开工准入——终态派工单阻断、超期放行带黄标、人单不匹配代报留痕双字段。
 */
@SpringBootTest
class ReportingDbTest {

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
    private MrpReportingDao reportingDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MrpMo mo;
    private MrpDispatch dispatch;

    private static final LocalDate START = LocalDate.of(2028, 8, 1);
    private static final LocalDate END = LocalDate.of(2028, 8, 28);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("rpt-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "报工测试产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "报工测试子件", "BUY");
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
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='WORKHOUR_ALERT'");
        jdbc.update("DELETE FROM erp_mrp_reporting");
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC-%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP-%'");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        MoSeed.cleanup(jdbc);
    }

    // ---------- 2.1 状态机 ----------

    @Test
    void openConfirmReadonlyAndTotals() {
        // 创建 OPEN：单号、快照带入、报告人与派工确认人留痕
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        assertNotNull(rpt.getRptNo());
        assertTrue(rpt.getRptNo().matches("RP\\d{8}\\d{3}"), "单号 RP+日期+流水：" + rpt.getRptNo());
        assertEquals(MrpReporting.ST_OPEN, rpt.getStatus(), "创建态 OPEN");
        assertEquals(mo.getMoNo(), rpt.getMoNo(), "工单快照带入");
        assertEquals(dispatch.getOpSeq(), rpt.getOpSeq(), "工序快照带入");
        assertEquals("rpt-tester", rpt.getReporter(), "报告人 = 当前操作账号");
        assertEquals(dispatch.getConfirmBy(), rpt.getDispatchOwner(),
                "派工确认人 = 责任指派人（人单判定基准）");
        assertEquals(MrpReporting.MODE_BATCH, rpt.getMode(), "默认批量模式");
        assertNotNull(rpt.getStartAt(), "实际开工时间落库");

        // 同工序第二张（多次提交，多单累计口径）
        MrpReporting rpt2 = reportingService.open(dispatch.getId(), MrpReporting.MODE_PIECE);
        assertEquals(MrpReporting.MODE_PIECE, rpt2.getMode(), "逐件模式可指定");

        // 确认 CAS：首确认通过
        MrpReporting confirmed = reportingService.confirm(rpt.getId());
        assertEquals(MrpReporting.ST_CONFIRMED, confirmed.getStatus(), "确认态");
        assertNotNull(confirmed.getConfirmAt(), "确认时间留痕");
        assertEquals("rpt-tester", confirmed.getConfirmBy(), "确认人留痕");

        // 重复确认 409
        ServiceException dup = assertThrows(ServiceException.class,
                () -> reportingService.confirm(rpt.getId()));
        assertEquals(409, dup.getCode(), "重复确认 → 409：" + dup.getMessage());

        // 确认后只读：requireOpen 422、discard 422（BR-4.5-25）
        ServiceException ro = assertThrows(ServiceException.class,
                () -> reportingService.requireOpen(rpt.getId()));
        assertEquals(422, ro.getCode(), "确认后写操作 → 422：" + ro.getMessage());
        assertTrue(ro.getMessage().contains("冲销"), "提示仅可冲销：" + ro.getMessage());
        ServiceException del = assertThrows(ServiceException.class,
                () -> reportingService.discard(rpt.getId()));
        assertEquals(422, del.getCode(), "确认后删除 → 422");

        // 放弃草稿：rpt2（仍 OPEN）可删
        reportingService.discard(rpt2.getId());
        assertEquals(0, reportingDao.selectCount(new LambdaQueryWrapper<MrpReporting>()
                .eq(MrpReporting::getId, rpt2.getId())), "草稿删除成功");

        // 累计（confirmedTotals）：rpt 无产出行 → 全 0；口径键齐全
        Map<String, Object> totals = reportingService.confirmedTotals(mo.getId(), dispatch.getOpSeq());
        assertEquals(0, ((Number) totals.get("goodQty")).longValue(), "无产出行合格 0");
        assertEquals(1L, totals.get("rptCount"), "已确认 1 张（rpt2 已删不计）");
        assertNotNull(totals.get("inputQty"), "累计投入键存在（D2 口径）");

        // 未登录 401
        MoTestAuth.logout();
        ServiceException anon = assertThrows(ServiceException.class,
                () -> reportingService.open(dispatch.getId(), null));
        assertEquals(401, anon.getCode(), "未认证 → 401");
        // 无权角色 403（仅 USER）
        MoTestAuth.login("rpt-user", "ROLE_USER");
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> reportingService.open(dispatch.getId(), null));
        assertEquals(403, forbidden.getCode(), "无录入角色 → 403");
        MoTestAuth.login("rpt-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");
    }

    @Test
    void openNonexistentDispatch422() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.open("no-such-dispatch", null));
        assertEquals(422, e.getCode(), "派工单不存在 → 422：" + e.getMessage());
    }

    // ---------- 2.5 工时分项与异常待办（FR-4.5-5-6） ----------

    @Test
    void hoursDeviationAlertConfirmAndPass() {
        // 种子定额：标准加工工时 1h（runHours），准备工时无基线不比对
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);

        // ① 8h/标准 1h → 偏离 700% > 50% → 生成主管待办、confirm 阻断
        reportingService.saveHours(rpt.getId(), BigDecimal.ZERO, new BigDecimal("8"),
                new BigDecimal("2"));
        assertEquals("1", queryStr("SELECT HOUR_ALERT_FLAG FROM erp_mrp_reporting WHERE ID=?",
                rpt.getId()), "超限置 HOUR_ALERT_FLAG");
        Long notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='WORKHOUR_ALERT' AND BIZ_ID=?",
                Long.class, rpt.getId());
        assertEquals(1L, notices, "主管待办已推送（WORKHOUR_ALERT）");
        ServiceException blocked = assertThrows(ServiceException.class,
                () -> reportingService.confirm(rpt.getId()));
        assertEquals(422, blocked.getCode(), "待确认时 confirm 阻断：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("工时异常"), "提示工时异常：" + blocked.getMessage());

        // 主管确认入账 → confirm 放行
        MoTestAuth.login("hours-mgr", "ROLE_PLAN_MGR");
        reportingService.confirmHours(rpt.getId());
        assertNotNull(queryStr("SELECT HOUR_CONFIRM_BY FROM erp_mrp_reporting WHERE ID=?",
                rpt.getId()), "确认人留痕");
        MoTestAuth.login("rpt-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");
        MrpReporting confirmed = reportingService.confirm(rpt.getId());
        assertEquals(MrpReporting.ST_CONFIRMED, confirmed.getStatus(), "确认后入账");

        // ② 新单：1.167h/标准 1h → 偏离 16.7% ≤ 50% → 直接计入（无待办）
        MrpReporting rpt2 = reportingService.open(dispatch.getId(), null);
        reportingService.saveHours(rpt2.getId(), BigDecimal.ZERO, new BigDecimal("1.167"),
                BigDecimal.ZERO);
        assertEquals("0", queryStr("SELECT HOUR_ALERT_FLAG FROM erp_mrp_reporting WHERE ID=?",
                rpt2.getId()), "未超限无告警");
        MrpReporting ok = reportingService.confirm(rpt2.getId());
        assertEquals(MrpReporting.ST_CONFIRMED, ok.getStatus(), "未超限直接提交成功");
    }

    @Test
    void confirmHoursRequiresAlert() {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        MoTestAuth.login("hours-mgr", "ROLE_PLAN_MGR");
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.confirmHours(rpt.getId()));
        assertEquals(422, e.getCode(), "无异常待确认 → 422：" + e.getMessage());
        MoTestAuth.login("rpt-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");
    }

    // ---------- 2.7 权限矩阵（spec「操作工无权冲销」「计划员可用管理台」） ----------

    @Test
    void permissionMatrixEntryVsAdmin() {
        // 操作工（SHOP_FLOOR）：录入 200、冲销申请 403
        MoTestAuth.login("pm-floor", "ROLE_SHOP_FLOOR");
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        assertEquals(MrpReporting.ST_OPEN, rpt.getStatus(), "SHOP_FLOOR 录入成功");
        ServiceException noReversal = assertThrows(ServiceException.class,
                () -> reportingService.applyReversal(rpt.getId(), "越权测试"));
        assertEquals(403, noReversal.getCode(), "SHOP_FLOOR 冲销 → 403：" + noReversal.getMessage());
        MrpReporting confirmed = reportingService.confirm(rpt.getId());
        assertEquals(MrpReporting.ST_CONFIRMED, confirmed.getStatus(), "SHOP_FLOOR 提交报工成功");
        // 预建一张 OPEN 单供 PLANNER confirm 越权测试（open 本身也是录入类）
        MrpReporting openForPlanner = reportingService.open(dispatch.getId(), null);

        // 计划员（PLANNER）：冲销申请 200（管理台发起）、录入类 403
        MoTestAuth.login("pm-planner", "ROLE_PLANNER");
        Map<String, Object> appr = reportingService.applyReversal(rpt.getId(), "计划员冲销");
        assertNotNull(appr.get("apprNo"), "PLANNER 冲销申请成功（spec「计划员可用管理台」）");
        ServiceException entryForbidden = assertThrows(ServiceException.class,
                () -> reportingService.confirm(openForPlanner.getId()));
        assertEquals(403, entryForbidden.getCode(),
                "PLANNER 提交报工 → 403（录入类限 SHOP_FLOOR/ADMIN）：" + entryForbidden.getMessage());

        // USER 无任何权限：录入 403、查询 403
        MoTestAuth.login("pm-user", "ROLE_USER");
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> reportingService.open(dispatch.getId(), null));
        assertEquals(403, forbidden.getCode(), "ROLE_USER 录入 → 403");
        ServiceException readForbidden = assertThrows(ServiceException.class,
                () -> reportingService.list(mo.getId(), null, null));
        assertEquals(403, readForbidden.getCode(), "ROLE_USER 查询 → 403");

        // 未登录 401（open 路径）
        MoTestAuth.logout();
        ServiceException anon = assertThrows(ServiceException.class,
                () -> reportingService.open(dispatch.getId(), null));
        assertEquals(401, anon.getCode(), "未认证 → 401");
        MoTestAuth.login("rpt-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");
    }

    // ---------- 2.2 开工准入 ----------

    @Test
    void admissionTerminalBlockedOverdueAllowedAndProxyTrace() {
        // ① 终态阻断：取消派工单 → 开工 422
        dispatchService.cancel(dispatch.getId(), "测试取消");
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.open(dispatch.getId(), null));
        assertEquals(422, e.getCode(), "终态派工单阻断 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("取消") || e.getMessage().contains("终态"),
                "阻断提示含终态原因：" + e.getMessage());

        // ② 超期放行带黄标：计划开工日为昨天的 RELEASED 派工单 → 开工成功 + OVERDUE_FLAG
        //    （① 已将原派工单取消，工序回 PENDING，重新生成一张）
        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        MrpDispatch d2 = dispatchService.generate(rows).get(0);
        d2 = dispatchService.confirm(d2.getId());
        jdbc.update("UPDATE erp_mrp_dispatch SET PLAN_START=? WHERE ID=?",
                java.sql.Date.valueOf(LocalDate.now().minusDays(2)), d2.getId());
        MrpReporting overdue = reportingService.open(d2.getId(), null);
        assertEquals("1", queryStr("SELECT OVERDUE_FLAG FROM erp_mrp_reporting WHERE ID=?",
                overdue.getId()), "超期放行带黄标 OVERDUE_FLAG=1");

        // ③ 人单不匹配：他人确认的派工单 → 代报留痕双字段
        MoTestAuth.login("rpt-b", "ROLE_SHOP_FLOOR", "ROLE_ADMIN");
        MrpReporting proxy = reportingService.open(d2.getId(), null);
        assertEquals("1", queryStr("SELECT PROXY_FLAG FROM erp_mrp_reporting WHERE ID=?",
                proxy.getId()), "代报标记 = 1");
        assertEquals("rpt-tester", queryStr("SELECT DISPATCH_OWNER FROM erp_mrp_reporting WHERE ID=?",
                proxy.getId()), "DISPATCH_OWNER = 原派工确认人（b 视角的他人）");
        assertEquals("rpt-b", queryStr("SELECT REPORTER FROM erp_mrp_reporting WHERE ID=?",
                proxy.getId()), "REPORTER = 实际代报人");
        MoTestAuth.logout();
        MoTestAuth.login("rpt-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");
    }

    private String queryStr(String sql, Object... args) {
        Object v = jdbc.queryForObject(sql, String.class, args);
        return v == null ? null : v.toString();
    }
}
