package com.erp.mo;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpReporting;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.common.ServiceException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关键工序 IPQC 自动建批与阻断库测（task 3.7，spec in-process-quality 三场景 +
 * inspection-lot MODIFIED「关键工序 IPQC 未决阻断报工」）：
 * 关键工序首次完工报工建 IPQC 批、未决仅阻断本工序（他工序放行）、
 * IPQC_ENFORCE=OFF 降级放行。
 */
@SpringBootTest
class ReportingIpqcDbTest {

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
    private MrpDispatch keyDispatch;
    private Integer keyOpSeq;
    private Integer plainOpSeq;

    private static final LocalDate START = LocalDate.of(2029, 5, 28);
    private static final LocalDate END = LocalDate.of(2029, 6, 25);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("ipqc-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "IPQC测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "IPQC测试子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        // 两工序路线——第二道标记关键工序
        MoSeed.publishRoutingMulti(routingService, operationService,
                workCenterService, standardService, jdbc, product, 2);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        mo = MoSeed.releaseMo(moService, product, "100", START, END);
        // 快照：第二道 KEY_OP_FLAG=1
        List<MrpMoOp> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId()).orderByAsc(MrpMoOp::getOpSeq));
        assertEquals(2, ops.size(), "两工序快照");
        plainOpSeq = ops.get(0).getOpSeq();
        keyOpSeq = ops.get(1).getOpSeq();
        jdbc.update("UPDATE erp_mrp_mo_op SET KEY_OP_FLAG='1' WHERE ID=?", ops.get(1).getId());

        // 生成并确认两道工序的派工单
        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        List<MrpDispatch> created = dispatchService.generate(rows);
        assertEquals(2, created.size(), "两工序派工");
        keyDispatch = dispatchService.confirm(created.stream()
                .filter(d -> keyOpSeq.equals(d.getOpSeq())).findFirst().orElseThrow().getId());
        MrpDispatch plain = dispatchService.confirm(created.stream()
                .filter(d -> plainOpSeq.equals(d.getOpSeq())).findFirst().orElseThrow().getId());
        // 普通工序（首道）会有 FAI——关 FAI 聚焦 IPQC；首道也标关键来测「他工序」对照不行，
        // 用非首道非关键的场景不可得（只有两道），故关 FAI 后首道仅受 FAI 影响 = 无
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        assertNotNull(plain);
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        sysParam.setValue("IPQC_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
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

    private InspectionLot ipqcLot() {
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
                mo.getId() + "#" + keyOpSeq).stream().findFirst().orElse(null);
    }

    private MrpReporting reportOn(MrpDispatch dispatch, int good) {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal(good), BigDecimal.ZERO,
                null, false);
        return reportingService.confirm(rpt.getId());
    }

    @Test
    void keyOpCreatesIpqcLotAndBlocksOnlyThatOp() {
        // ① 关键工序首次完工报工 → 自动建 IPQC 批（spec「关键工序自动建批」）
        reportOn(keyDispatch, 10);
        InspectionLot lot = ipqcLot();
        assertNotNull(lot, "IPQC 批已建");
        assertEquals(mo.getId() + "#" + keyOpSeq, lot.getRefId(),
                "refId = moId#opSeq（无 FAI 后缀）");
        assertEquals("IPQC", jdbc.queryForObject(
                "SELECT LOT_TYPE FROM erp_qms_inspection_lot WHERE ID=?", String.class,
                lot.getId()), "lotType=IPQC");

        // ② 未决仅阻断本工序（spec「本工序阻断他工序放行」）
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportOn(keyDispatch, 5));
        assertEquals(422, e.getCode(), "关键工序未决二次提交 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("过程检验"), "提示过程检验：" + e.getMessage());

        // 非关键（首道）工序不受影响——他工序放行（FAI 已 OFF）
        MrpReporting plain = reportOn(
                dispatchService.list(null, null).stream()
                        .filter(d -> plainOpSeq.equals(d.getOpSeq())
                                && mo.getId().equals(d.getMoId()))
                        .findFirst().orElseThrow(),
                8);
        assertEquals(MrpReporting.ST_CONFIRMED, plain.getStatus(), "他工序放行");

        // ③ 放行本工序批 → 二次提交放行
        jdbc.update("UPDATE erp_qms_inspection_lot SET STATUS='RELEASED', RESULT='PASS' "
                + "WHERE REF_ID=?", mo.getId() + "#" + keyOpSeq);
        MrpReporting ok = reportOn(keyDispatch, 5);
        assertEquals(MrpReporting.ST_CONFIRMED, ok.getStatus(), "合格后放行");
    }

    @Test
    void ipqcEnforceOffDegradesToPass() {
        reportOn(keyDispatch, 10);
        assertNotNull(ipqcLot(), "IPQC 批已建");
        sysParam.setValue("IPQC_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        MrpReporting ok = reportOn(keyDispatch, 5);
        assertEquals(MrpReporting.ST_CONFIRMED, ok.getStatus(), "OFF 放行");
    }

    @Test
    void nonKeyOpNeverCreatesIpqcLot() {
        // 首道（非关键）报工：会建 FAI 批（首道触发，OFF 只降级阻断不关建批），
        // 但不建 IPQC 批（refId 无 FAI 后缀的 WORKORDER 批应为 0）
        reportOn(
                dispatchService.list(null, null).stream()
                        .filter(d -> plainOpSeq.equals(d.getOpSeq())
                                && mo.getId().equals(d.getMoId()))
                        .findFirst().orElseThrow(),
                6);
        assertNull(ipqcLot(), "非关键工序无 IPQC 批");
        Long ipqcLots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER'"
                        + " AND REF_ID NOT LIKE '%#FAI'", Long.class);
        assertEquals(0L, ipqcLots, "无 IPQC 批（仅 FAI 批可存在）");
        Long faiLots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER'"
                        + " AND REF_ID LIKE '%#FAI'", Long.class);
        assertEquals(1L, faiLots, "首道 FAI 批已建（首道触发，独立于 IPQC）");
    }
}
