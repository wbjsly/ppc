package com.erp.mo;

import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpReporting;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.ReportingService;
import com.erp.service.mrp.ReportingStatsService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工序级良率统计库测（task 3.2，design D2 唯一口径）：
 * 规格例题数值 32/35=91.4% 偏差 6.7%（00-erp-spec L2579）、分母 0 返回 null、
 * 冲销后回拨、YIELD_TOLERANCE 阈值判定（超/不超）。
 */
@SpringBootTest
class ReportingStatsDbTest {

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
    private ReportingStatsService statsService;
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
    private MrpDispatch dispatch;
    private Integer opSeq;

    private static final LocalDate START = LocalDate.of(2029, 1, 8);
    private static final LocalDate END = LocalDate.of(2029, 2, 5);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("stats-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "良率统计产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "良率统计子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        mo = MoSeed.releaseMo(moService, product, "50", START, END);
        // 快照良率设 98（规格例题标准良率）
        jdbc.update("UPDATE erp_mrp_mo_op SET STANDARD_YIELD=98 WHERE MO_ID=?", mo.getId());
        // 本测试聚焦良率口径——关首件卡控（多单 confirm 不被 FAI 批阻断）
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");

        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());
        opSeq = dispatch.getOpSeq();
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
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

    /** 造一张 CONFIRMED 报工（指定合格/不合格） */
    private MrpReporting confirmWith(BigDecimal good, BigDecimal ng) {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), good, ng, null, false);
        return reportingService.confirm(rpt.getId());
    }

    @Test
    void specExampleYieldAndDeviation() {
        // 规格例题（00-erp-spec L2579）：投入 35、合格 32 → 良率 91.4%，标准 98 → 偏差 6.7%
        // 分两单：30 合格+3 不合格、2 合格（累计投入 35 = 33+2、合格 32）
        confirmWith(new BigDecimal("30"), new BigDecimal("3"));
        confirmWith(new BigDecimal("2"), BigDecimal.ZERO);

        Map<String, Object> y = statsService.yieldOf(mo.getId(), opSeq);
        assertEquals(0, ((BigDecimal) y.get("inputQty")).compareTo(new BigDecimal("35")),
                "累计投入 35：" + y.get("inputQty"));
        assertEquals(0, ((BigDecimal) y.get("goodQty")).compareTo(new BigDecimal("32")),
                "累计合格 32");
        BigDecimal actual = (BigDecimal) y.get("actualYield");
        assertEquals(0, actual.compareTo(new BigDecimal("91.43")),
                "良率 91.43%（32/35，规格例题 91.4%）：" + actual);
        assertEquals(0, ((BigDecimal) y.get("standardYield")).compareTo(new BigDecimal("98")),
                "标准良率取快照 98");
        BigDecimal deviation = (BigDecimal) y.get("deviation");
        assertTrue(deviation.compareTo(new BigDecimal("6.5")) > 0
                        && deviation.compareTo(new BigDecimal("7.0")) <= 0,
                "偏差 ≈6.7%（|91.43-98|/98）：" + deviation);

        // 阈值判定：6.7% ≤ 10% → 不超
        Map<String, Object> d = statsService.deviationExceeded(mo.getId(), opSeq);
        assertEquals(false, d.get("exceeded"), "6.7% 不超 10% 阈值");
    }

    @Test
    void zeroDenominatorReturnsNullAndToleranceExceeded() {
        // 分母 0 → actual/deviation null、不超阈值（不误冻结）
        Map<String, Object> empty = statsService.yieldOf(mo.getId(), opSeq);
        assertNull(empty.get("actualYield"), "无投入 actualYield=null");
        assertNull(empty.get("deviation"), "无投入 deviation=null");
        Map<String, Object> d0 = statsService.deviationExceeded(mo.getId(), opSeq);
        assertEquals(false, d0.get("exceeded"), "分母 0 不超阈值");

        // 低良率：投入 35 合格 29 → 82.86%，偏差 |82.86-98|/98=15.4% > 10% → 超
        confirmWith(new BigDecimal("29"), new BigDecimal("6"));
        Map<String, Object> d = statsService.deviationExceeded(mo.getId(), opSeq);
        assertEquals(true, d.get("exceeded"), "15.4% > 10% 超阈值");
        BigDecimal dev = (BigDecimal) d.get("deviation");
        assertTrue(dev.compareTo(new BigDecimal("15")) > 0, "偏差 15.4%：" + dev);
    }

    @Test
    void reversalRebacksYield() {
        // 初始 32/35（6.7% 不超）→ 冲销 30+3 单 → 回拨 2/2=100%
        MrpReporting first = confirmWith(new BigDecimal("30"), new BigDecimal("3"));
        confirmWith(new BigDecimal("2"), BigDecimal.ZERO);
        Map<String, Object> before = statsService.yieldOf(mo.getId(), opSeq);
        assertEquals(0, ((BigDecimal) before.get("goodQty")).compareTo(new BigDecimal("32")),
                "冲销前 32");

        // 直接执行冲销反向（跳过审批——本测试只验证统计回拨口径，审批链在 2.6 已测）
        jdbc.update("UPDATE erp_mrp_reporting SET STATUS='REVERSED' WHERE ID=?", first.getId());

        Map<String, Object> after = statsService.yieldOf(mo.getId(), opSeq);
        assertEquals(0, ((BigDecimal) after.get("goodQty")).compareTo(new BigDecimal("2")),
                "回拨后 2：" + after.get("goodQty"));
        assertEquals(0, ((BigDecimal) after.get("inputQty")).compareTo(new BigDecimal("2")),
                "回拨后投入 2：" + after.get("inputQty"));
        assertEquals(1L, after.get("rptCount"), "回拨后确认单数 1（REVERSED 剔除，首单已冲）");
        assertNotNull(after.get("actualYield"), "回拨后良率可算");
    }

    @Test
    void standardYieldFromSnapshotOnly() {
        // 快照为唯一来源：直接改快照 → 判定跟随
        jdbc.update("UPDATE erp_mrp_mo_op SET STANDARD_YIELD=60 WHERE MO_ID=?", mo.getId());
        confirmWith(new BigDecimal("30"), new BigDecimal("5")); // 30/35=85.7%
        Map<String, Object> d = statsService.deviationExceeded(mo.getId(), opSeq);
        // |85.71-60|/60 = 42.9% > 10% → 超
        assertEquals(true, d.get("exceeded"), "快照 60 时偏差超阈值");
        // 无快照行（opSeq 不存在）→ standardYield null 不判定
        Map<String, Object> none = statsService.yieldOf(mo.getId(), 999);
        assertNull(none.get("standardYield"), "无快照行 standardYield=null");
        Map<String, Object> dn = statsService.deviationExceeded(mo.getId(), 999);
        assertFalse((Boolean) dn.get("exceeded"), "无标准良率不判定（不误冻结）");
    }
}
