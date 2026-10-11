package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.qms.NcrDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpReporting;
import com.erp.entity.qms.Ncr;
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
import com.erp.service.qms.NcrService;
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
 * 报工来源分级 NCR 库测（task 3.5，spec in-process-quality「不合格产出分级 NCR」三场景）：
 * 小额报废域内处置零 NCR、返工必走 NCR（refType/关联字段）、在制冻结不冻库存；
 * 补验：良率超差 YIELD NCR 挂冻结、NCR 关闭解在制冻结（spec「NCR 关闭解冻」）、
 * 处置成本 COPQ 归集读到 NCR（quality-cost「NCR 处置自动归集」）。
 */
@SpringBootTest
class ReportingNcrDbTest {

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
    private NcrService ncrService;
    @Autowired
    private NcrDao ncrDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MdmItem child;
    private MrpMo mo;
    private MrpDispatch dispatch;

    private static final LocalDate START = LocalDate.of(2029, 3, 19);
    private static final LocalDate END = LocalDate.of(2029, 4, 16);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("ncr-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER",
                "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "NCR测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "NCR测试子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        // 计划 100：测试报工累计不触发超产
        mo = MoSeed.releaseMo(moService, product, "100", START, END);
        jdbc.update("UPDATE erp_mrp_mo_op SET STANDARD_YIELD=98 WHERE MO_ID=?", mo.getId());

        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());
        cleanupNcr();
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        cleanupNcr();
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER'");
        jdbc.update("DELETE FROM erp_mrp_mo_freeze");
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

    private void cleanupNcr() {
        jdbc.update("DELETE l FROM erp_qms_ncr_log l JOIN erp_qms_ncr n ON l.NCR_ID=n.ID"
                + " WHERE n.REF_TYPE='WORKORDER'");
        jdbc.update("DELETE FROM erp_qms_copq_entry WHERE SOURCE_TYPE='NCR' AND NCR_ID IN "
                + "(SELECT ID FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER')");
        jdbc.update("DELETE FROM erp_qms_capa WHERE NCR_ID IN "
                + "(SELECT ID FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER')");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER'");
    }

    private Ncr workorderNcr() {
        return ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                .eq(Ncr::getRefType, "WORKORDER")
                .notIn(Ncr::getStatus, "CANCELLED")
                .orderByDesc(Ncr::getCreateDate)
                .last("LIMIT 1"));
    }

    private long workorderNcrCount() {
        return ncrDao.selectCount(new LambdaQueryWrapper<Ncr>()
                .eq(Ncr::getRefType, "WORKORDER"));
    }

    // ---------- spec 三场景 ----------

    @Test
    void smallScrapDisposedInDomainWithoutNcr() {
        // 不合格 3 件、处置=报废、NCR_AUTO_QTY=5 → 域内直接处置，不建 NCR
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        var out = reportingService.saveOutput(rpt.getId(), new BigDecimal("97"),
                new BigDecimal("3"), "SCRAP", false);
        assertEquals("1", jdbc.queryForObject(
                "SELECT DIRECT_DISPOSE_FLAG FROM erp_mrp_output WHERE ID=?", String.class,
                out.getId()), "域内直接处置标记");
        assertNull(out.getNcrId(), "无 NCR 关联");
        assertEquals(0, workorderNcrCount(), "小额报废零 NCR");
        // 冻结也不产生（域内处置不挡派工）
        assertNull(freezeService.activeOf(mo.getId()), "无在制冻结");
    }

    @Test
    void reworkAlwaysCreatesNcrWithWorkorderRef() {
        // 返工（不论数量）→ 必建 NCR，refType/关联字段正确
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        var out = reportingService.saveOutput(rpt.getId(), new BigDecimal("98"),
                new BigDecimal("2"), "REWORK", false);
        Ncr ncr = workorderNcr();
        assertNotNull(ncr, "返工建 NCR");
        assertEquals("WORKORDER", ncr.getRefType(), "refType=WORKORDER");
        assertEquals(rpt.getMoId() + "#" + dispatch.getOpSeq() + "#" + rpt.getRptNo(),
                ncr.getRefId(), "refId=moId#opSeq#rptNo");
        assertEquals(mo.getProductCode(), ncr.getItemCode(), "物料 = 工单产品");
        assertEquals("REWORK", ncr.getDisposition(), "预选处置返工");
        assertEquals("CREATED", ncr.getStatus(), "进评审队列（CREATED）");
        assertEquals("WORKORDER", ncr.getFreezeScope(), "冻结语义=WORKORDER");
        assertNotNull(ncr.getReviewDueTime(), "分级评审时限已排（BR-4.12-25）");
        assertEquals(ncr.getId(), out.getNcrId(), "产出行回联 NCR");

        // 幂等：同 refId 再触发返回同一张
        Ncr again = ncrService.createForWorkOrder(mo.getId(), dispatch.getOpSeq(),
                ncr.getRefId(), ncr.getItemCode(), null, BigDecimal.ONE, "REWORK",
                "MINOR", "重复触发");
        assertEquals(ncr.getId(), again.getId(), "同 refId 幂等");
        assertEquals(1, workorderNcrCount(), "仍只有 1 张");
    }

    @Test
    void workorderNcrFreezesInProcessNotStock() {
        // 在制冻结：挡派工/完工，不写库存冻结行
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal("95"),
                new BigDecimal("5"), "REWORK", false);
        var freeze = freezeService.activeOf(mo.getId());
        assertNotNull(freeze, "NCR 触发在制冻结");
        assertEquals("NCR", freeze.getReason(), "冻结原因=NCR");
        assertNotNull(freeze.getNcrId(), "冻结挂 NCR");
        // 库存无冻结行（AVAILABLE 未转 QC）——本测试子件库存 9999 扣投料后无 QC 变动
        BigDecimal qc = jdbc.queryForObject(
                "SELECT IFNULL(SUM(QTY - AVAILABLE_QTY),0) FROM erp_inv_stock WHERE ITEM_CODE=?",
                BigDecimal.class, child.getItemCode());
        BigDecimal reserved = jdbc.queryForObject(
                "SELECT IFNULL(SUM(RESERVED_QTY),0) FROM erp_mrp_reservation WHERE MO_ID=?",
                BigDecimal.class, mo.getId());
        assertEquals(0, qc.compareTo(reserved),
                "库存不可用量仅 = 预扣（NCR 未动库存，无 QC 冻结行）：" + qc + " vs " + reserved);
        // 挡派工
        ServiceException e = assertThrows(ServiceException.class,
                () -> freezeService.assertNotFrozen(mo.getId(), dispatch.getWcCode()));
        assertTrue(e.getMessage().contains("冻结"), "挡派工提示：" + e.getMessage());
    }

    // ---------- 补验：YIELD NCR + 关闭解冻 + COPQ 归集 ----------

    @Test
    void yieldOverdeviationCreatesYieldNcrLinkedToFreeze() {
        // 良率超差（29/33=87.88%，偏差 10.33% > 10%）→ 冻结挂 YIELD NCR
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal("29"),
                new BigDecimal("4"), "SCRAP", false);
        reportingService.confirm(rpt.getId());
        var freeze = freezeService.activeOf(mo.getId());
        assertNotNull(freeze, "YIELD 冻结存在");
        assertEquals("YIELD", freeze.getReason(), "原因=YIELD");
        assertNotNull(freeze.getNcrId(), "冻结挂 NCR");
        Ncr ncr = ncrDao.selectById(freeze.getNcrId());
        assertNotNull(ncr, "YIELD NCR 存在");
        assertTrue(ncr.getRefId().endsWith("#YIELD"), "refId 后缀 #YIELD：" + ncr.getRefId());
        assertTrue("MINOR".equals(ncr.getSeverity()) || "MAJOR".equals(ncr.getSeverity()),
                "严重度分级 MINOR/MAJOR：" + ncr.getSeverity());
        // 偏差 10.33% ≤ 2×阈值 20% → MINOR → 默认 MO 级（偏差 D6）
        assertEquals("MINOR", ncr.getSeverity(), "小幅超差 MINOR");
        assertEquals("MO", freeze.getScope(), "MINOR 保持工单级（spec 默认工单级冻结）");
    }

    @Test
    void ncrCloseReleasesInProcessFreezeAndCopqAttributed() {
        // 返工 NCR → 在制冻结；评审报废 → 处置确认（含 COPQ 归集）→ 关闭 → 解冻
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal("95"),
                new BigDecimal("5"), "REWORK", false);
        Ncr ncr = workorderNcr();
        assertNotNull(ncr, "NCR 已建");
        assertNotNull(freezeService.activeOf(mo.getId()), "冻结存在");

        // 评审选定报废（REWORK → SCRAP 允许，评审即改处置）
        ncrService.review(ncr.getId(), "SCRAP", "评审改判报废，同意");
        assertEquals("SCRAPPING", ncrDao.selectById(ncr.getId()).getStatus(), "进报废处置态");

        // 处置确认（回填报废单号）+ 成本 800 → COPQ INTERNAL_FAILURE 归集
        ncrService.confirmDisposed(ncr.getId(), "SCRAP-2029-0001", new BigDecimal("800"));
        Long copq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_copq_entry WHERE CATEGORY='INTERNAL_FAILURE'"
                        + " AND SOURCE_TYPE='NCR' AND NCR_ID=? AND AMOUNT=800",
                Long.class, ncr.getId());
        assertEquals(1L, copq, "COPQ 内部失败归集读到 NCR（quality-cost「NCR 处置自动归集」）");

        // 关闭链需 CAPA（CLOSED + VALID）——手工构造（BR-4.12-29 关闭校验为既有行为）
        jdbc.update("INSERT INTO erp_qms_capa (ID, CAPA_NO, NCR_ID, NCR_NO, TITLE, STATUS,"
                        + " VERIFY_RESULT, CURRENT_STEP, SEVERITY, SOURCE_TYPE, DEL_FLAG, VER_NO)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?, '0', 0)",
                "capa-ncr-test", "CA-2029-000001", ncr.getId(), ncr.getNcrNo(),
                "报工不合格纠正", "CLOSED", "VALID", "D8", "MINOR", "MANUAL");
        // 关闭解冻
        ncrService.close(ncr.getId(), "处置完成，关闭");
        assertEquals("CLOSED", ncrDao.selectById(ncr.getId()).getStatus(), "NCR 已关闭");
        assertNull(freezeService.activeOf(mo.getId()), "NCR 关闭解在制冻结（spec「NCR 关闭解冻」）");
        Long unfreeze = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='YIELD_UNFREEZE'",
                Long.class);
        assertTrue(unfreeze >= 1, "解冻通知推送：" + unfreeze);
        // 解冻后放行
        freezeService.assertNotFrozen(mo.getId(), dispatch.getWcCode());
    }

    @Test
    void ncrCancelAlsoReleasesFreeze() {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal("96"),
                new BigDecimal("4"), "CONCESSION", false);
        Ncr ncr = workorderNcr();
        assertNotNull(ncr, "NCR 已建");
        assertNotNull(freezeService.activeOf(mo.getId()), "冻结存在");
        ncrService.cancel(ncr.getId(), "误报，判定推翻");
        assertEquals("CANCELLED", ncrDao.selectById(ncr.getId()).getStatus(), "已作废");
        assertNull(freezeService.activeOf(mo.getId()), "作废同样解在制冻结");
    }
}
