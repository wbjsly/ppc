package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpReporting;
import com.erp.entity.qms.InspectionLot;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首件检验自动建批与阻断库测（task 3.6，spec in-process-quality 三场景）：
 * 首次报工自动建批（refId=moId#opSeq#FAI）、未出合格结论二次提交 422 阻断、
 * FAI_ENFORCE=OFF 降级放行（黄提示语义在前端）、合格（RELEASED）后放行。
 */
@SpringBootTest
class ReportingFaiDbTest {

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

    private static final LocalDate START = LocalDate.of(2029, 4, 23);
    private static final LocalDate END = LocalDate.of(2029, 5, 21);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("fai-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "首件测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "首件测试子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        mo = MoSeed.releaseMo(moService, product, "100", START, END);

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
        jdbc.update("DELETE FROM erp_mrp_mo_freeze");
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

    private InspectionLot faiLot() {
        return jdbc.query("SELECT ID, LOT_NO, STATUS, REF_ID FROM erp_qms_inspection_lot "
                        + "WHERE REF_TYPE='WORKORDER' AND REF_ID=?",
                (rs, i) -> {
                    InspectionLot l = new InspectionLot();
                    l.setId(rs.getString("ID"));
                    l.setLotNo(rs.getString("LOT_NO"));
                    l.setStatus(rs.getString("STATUS"));
                    l.setRefId(rs.getString("REF_ID"));
                    return l;
                },
                mo.getId() + "#" + opSeq + "#FAI").stream().findFirst().orElse(null);
    }

    /** 第一张报工（含合格产出）确认 → 触发首件建批 */
    private MrpReporting firstConfirm() {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal("10"), BigDecimal.ZERO,
                null, false);
        return reportingService.confirm(rpt.getId());
    }

    private MrpReporting secondReport() {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal("10"), BigDecimal.ZERO,
                null, false);
        return reportingService.confirm(rpt.getId());
    }

    @Test
    void firstReportCreatesFaiLotAndBlocksSecond() {
        // ① 首次报工自动建批（spec「首次报工自动建批」）
        firstConfirm();
        InspectionLot lot = faiLot();
        assertNotNull(lot, "首件批已建");
        assertEquals(mo.getId() + "#" + opSeq + "#FAI", lot.getRefId(),
                "refId = moId#opSeq#FAI");
        assertEquals("IPQC", jdbc.queryForObject(
                "SELECT LOT_TYPE FROM erp_qms_inspection_lot WHERE ID=?", String.class,
                lot.getId()), "lotType=IPQC（FAI 以 IPQC 承载，design D5）");
        assertEquals("WORKORDER", jdbc.queryForObject(
                "SELECT REF_TYPE FROM erp_qms_inspection_lot WHERE ID=?", String.class,
                lot.getId()), "refType=WORKORDER");

        // ② 未出合格结论二次提交 → 422 阻断（spec「未合格阻断」）
        ServiceException e = assertThrows(ServiceException.class, this::secondReport);
        assertEquals(422, e.getCode(), "首件未决二次提交 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("首件"), "提示首件：" + e.getMessage());

        // ③ 检验合格（放行）→ 二次提交放行
        jdbc.update("UPDATE erp_qms_inspection_lot SET STATUS='RELEASED', RESULT='PASS' "
                + "WHERE REF_ID=?", mo.getId() + "#" + opSeq + "#FAI");
        MrpReporting ok = secondReport();
        assertEquals(MrpReporting.ST_CONFIRMED, ok.getStatus(), "合格后放行");
    }

    @Test
    void faiEnforceOffDegradesToPass() {
        firstConfirm();
        assertNotNull(faiLot(), "首件批已建");
        // OFF 降级：未决也放行（spec「开关降级放行」黄提示语义在前端）
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        MrpReporting ok = secondReport();
        assertEquals(MrpReporting.ST_CONFIRMED, ok.getStatus(), "OFF 放行");
    }

    @Test
    void exemptAndZeroGoodSkipLot() {
        // 无合格产出的报工不触发首件建批（GOOD=0 不算「首次产出」）
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), BigDecimal.ZERO, new BigDecimal("5"),
                "SCRAP", false);
        reportingService.confirm(rpt.getId());
        // 小额报废（5 件 = NCR_AUTO_QTY 不小于阈值 → ≥5 建 NCR，但首件只看 GOOD>0）
        assertEquals(null, faiLot(), "GOOD=0 不建首件批");
    }
}
