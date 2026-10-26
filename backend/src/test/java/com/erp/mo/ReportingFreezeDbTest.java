package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpMoFreezeDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoFreeze;
import com.erp.entity.mrp.MrpReporting;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.MoFreezeService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 良率冻结与解冻库测（task 3.3 + 3.4，spec in-process-quality 四场景 + 挂点三场景）：
 * 超差冻结+通知、参数 WC 冻全线、回拨解冻+解冻通知不重复推、
 * 挂点：冻结中派工 L1 阻断（兑现 5.6 D12）、冻结中完工阻断、解冻后放行。
 * NCR 关闭解冻场景在 ReportingNcrDbTest（3.5 建 NCR 后接回调）。
 */
@SpringBootTest
class ReportingFreezeDbTest {

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
    private MoFreezeService freezeService;
    @Autowired
    private MrpMoFreezeDao freezeDao;
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

    private static final LocalDate START = LocalDate.of(2029, 2, 13);
    private static final LocalDate END = LocalDate.of(2029, 3, 13);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("freeze-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "冻结测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "冻结测试子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        // 计划 200：多次低良率单累计 35×4=140 不触发超产挂起（本测试聚焦冻结链）
        mo = MoSeed.releaseMo(moService, product, "200", START, END);
        jdbc.update("UPDATE erp_mrp_mo_op SET STANDARD_YIELD=98 WHERE MO_ID=?", mo.getId());

        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());
        opSeq = dispatch.getOpSeq();
        setFreezeScope("MO");
        // 多次 confirm 同工序——关首件卡控（本测试聚焦冻结链）
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
    }

    private void setFreezeScope(String v) {
        sysParam.setValue("YIELD_FREEZE_SCOPE", v, "ENUM", "MRP",
                "良率冻结作用域（测试切换）", "test");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        setFreezeScope("MO");
        sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER'");
        jdbc.update("DELETE FROM erp_mrp_mo_freeze");
        jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID IN (SELECT ID FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER')");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('YIELD_ALERT','YIELD_UNFREEZE')");
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

    /**
     * 造低良率 CONFIRMED 报工（29/33 = 87.88% → 偏差 10.33% > 10%）触发 YIELD 冻结。
     * 不合格 4 件走 SCRAP 且 < NCR_AUTO_QTY=5 → 域内豁免不建处置 NCR（本测试聚焦 YIELD 链）。
     */
    private MrpReporting lowYieldReport() {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal("29"), new BigDecimal("4"),
                "SCRAP", false);
        return reportingService.confirm(rpt.getId());
    }

    private long noticeCount(String bizType) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE=? AND BIZ_ID=?",
                Long.class, bizType, mo.getId());
        return n == null ? 0 : n;
    }

    // ---------- 3.3 ----------

    @Test
    void overdeviationFreezesNotifiesAndRebackReleases() {
        // 超差冻结 + 通知
        lowYieldReport();
        MrpMoFreeze active = freezeService.activeOf(mo.getId());
        assertNotNull(active, "超差后活动冻结存在");
        assertEquals(MrpMoFreeze.SCOPE_MO, active.getScope(), "默认 MO 级（偏差 D6）");
        assertEquals("YIELD", active.getReason(), "良率原因");
        assertTrue(active.getDeviationValue().compareTo(new BigDecimal("10")) > 0,
                "偏差 > 10%：" + active.getDeviationValue());
        assertEquals(2L, noticeCount("YIELD_ALERT"),
                "YIELD_ALERT 推送两角色（主管+质量工程师，幂等含接收方维度）");
        assertTrue(active.getNoticeIds().contains("ROLE_SHOP_FLOOR"), "接收方含车间主管");
        assertTrue(active.getNoticeIds().contains("ROLE_QUALITY_ENG"), "接收方含质量工程师");

        // 参数 WC 冻全线：切参数后新冻结用 WC 级（先解冻再超差）
        freezeService.release(mo.getId(), "测试切换", "SYSTEM");
        setFreezeScope("WC");
        lowYieldReport();
        MrpMoFreeze wc = freezeService.activeOf(mo.getId());
        assertEquals(MrpMoFreeze.SCOPE_WC, wc.getScope(), "参数 WC 时冻全线");
        assertNotNull(wc.getWcCode(), "WC 级带 WC 编码");
        freezeService.release(mo.getId(), "测试回 MO", "SYSTEM");

        // 回拨解冻：冲销低良率单 → 良率回 100% → evaluate 自动解冻 + 解冻通知
        jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE='MO' WHERE PARAM_KEY='YIELD_FREEZE_SCOPE'");
        lowYieldReport();
        assertNotNull(freezeService.activeOf(mo.getId()), "解冻前有活动冻结");
        jdbc.update("UPDATE erp_mrp_reporting SET STATUS='REVERSED' "
                + "WHERE MO_ID=? AND STATUS='CONFIRMED'", mo.getId());
        Map<String, Object> eval = freezeService.evaluateAndFreeze(mo.getId(), opSeq, null);
        assertEquals(false, eval.get("frozen"), "回拨后不再冻结");
        assertEquals(true, eval.get("released"), "回拨自动解冻");
        assertNull(freezeService.activeOf(mo.getId()), "活动冻结已释放");
        assertEquals(2L, noticeCount("YIELD_UNFREEZE"), "解冻通知推原接收人两角色");

        // 解冻通知幂等：再评估一次不重复推
        freezeService.evaluateAndFreeze(mo.getId(), opSeq, null);
        assertEquals(2L, noticeCount("YIELD_UNFREEZE"), "重复评估解冻通知不重复推（幂等含接收方）");
    }

    // ---------- 3.4 冻结挂点（兑现 5.6 D12） ----------

    @Test
    void dispatchAndCompletionBlockedWhileFrozenThenReleased() {
        lowYieldReport();
        assertNotNull(freezeService.activeOf(mo.getId()), "冻结存在");

        // ① 派工生成被阻断（挂点 assertNotFrozen——5.6 D12 兑现）
        ServiceException e = assertThrows(ServiceException.class,
                () -> freezeService.assertNotFrozen(mo.getId(), dispatch.getWcCode()));
        assertEquals(422, e.getCode(), "冻结中断言 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("冻结"), "提示冻结中：" + e.getMessage());

        // 同口径：无冻结的工单放行（不抛异常即通过）
        freezeService.assertNotFrozen("other-mo", dispatch.getWcCode());

        // ② 解冻后放行
        freezeService.release(mo.getId(), "测试解冻", "SYSTEM");
        freezeService.assertNotFrozen(mo.getId(), dispatch.getWcCode()); // 不抛即放行
        assertNull(freezeService.activeOf(mo.getId()), "解冻后无活动冻结");
    }

    @Test
    void releaseIdempotentWhenNoActive() {
        assertEquals(false, freezeService.release(mo.getId(), "空释放", "SYSTEM"),
                "无活动冻结 release 返回 false");
        assertEquals(null, freezeService.activeOf("no-such-mo"), "未知工单无冻结");
    }
}
