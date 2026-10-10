package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.dao.mrp.MrpWorkCenterDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpSuggestionDao;
import com.erp.entity.mrp.MrpSuggestion;
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
 * 工单创建库测（task 3.1 七场景 / 3.2 PMO 三场景 / 3.3 快照隔离；含 2.2 各读入口结构）。
 */
@SpringBootTest
class MoCreateDbTest {

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
    private MrpMoBomDao moBomDao;
    @Autowired
    private MrpMoOpDao moOpDao;
    @Autowired
    private MrpSuggestionDao suggestionDao;
    @Autowired
    private MrpWorkCenterDao workCenterDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("mo-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        MoSeed.cleanup(jdbc);
    }

    private MdmItem mkMake(String name) {
        return MoSeed.mkItem(itemService, categoryCode, name, "MAKE");
    }

    private MdmItem mkBuy(String name) {
        return MoSeed.mkItem(itemService, categoryCode, name, "BUY");
    }

    private MrpMo head(MdmItem product, String qty, LocalDate planEnd) {
        MrpMo h = new MrpMo();
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(planEnd);
        return h;
    }

    @SuppressWarnings("unchecked")
    private List<String> warningsOf(Map<String, Object> out) {
        return (List<String>) out.get("warnings");
    }

    @SuppressWarnings("unchecked")
    private MrpMo moOf(Map<String, Object> out) {
        return (MrpMo) out.get("mo");
    }

    // ---------- 3.1 手工创建七场景 ----------

    @Test
    void createWithBomSnapshotOnly() {
        MdmItem product = mkMake("创建产品一");
        MdmItem child = mkBuy("创建子件一");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0.05");

        Map<String, Object> out = moService.create(head(product, "10", LocalDate.now().plusDays(7)));
        MrpMo mo = moOf(out);
        assertEquals(MrpMo.ST_PLANNED, mo.getStatus(), "创建落 PLANNED");
        assertTrue(mo.getMoNo().startsWith("MO-"), "MO_NO 流水前缀");
        assertEquals(0, mo.getQty().compareTo(new BigDecimal("10")), "计划数量");
        assertEquals("1", mo.getShortageFlag(), "无库存 → 缺料标记");
        // BOM 快照行（3.1 + 2.2 publishedBomSnapshot 读入口）
        List<MrpMoBom> lines = moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, mo.getId()));
        assertEquals(1, lines.size(), "快照行数");
        assertEquals(child.getItemCode(), lines.get(0).getItemCode(), "子项编码");
        assertEquals(0, lines.get(0).getUnitQty().compareTo(new BigDecimal("2")), "单位用量");
        assertEquals(0, lines.get(0).getLossRate().compareTo(new BigDecimal("0.0500")), "损耗率");
        assertEquals(1, lines.get(0).getTreeLevel(), "层级 1");
        // 无路线 → 放行 + 警告（FR-4.5-3-4 提示放行）
        assertTrue(warningsOf(out).stream().anyMatch(w -> w.contains("无已发布工艺路线")),
                "无路线警告：" + warningsOf(out));
        // 工序快照为空
        assertEquals(0, moOpDao.selectCount(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId())).intValue(), "无路线则无工序行");
    }

    @Test
    void createSnapshotsOpsWithStandardHours() {
        MdmItem product = mkMake("创建产品二");
        MdmItem child = mkBuy("创建子件二");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        String wcCode = MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);

        Map<String, Object> out = moService.create(head(product, "5", LocalDate.now().plusDays(5)));
        MrpMo mo = moOf(out);
        assertNotNull(mo.getSourceRoutingId(), "路线快照来源留痕");
        List<MrpMoOp> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId()));
        assertEquals(1, ops.size(), "工序快照行");
        assertEquals(wcCode, ops.get(0).getWcCode(), "工作中心分配");
        assertEquals(0, ops.get(0).getRunHours().compareTo(BigDecimal.ONE), "自动带入标准工时（定额矩阵）");
        assertEquals(0, ops.get(0).getLeadTime().compareTo(new BigDecimal("3")), "工序提前期");
        assertTrue(warningsOf(out).stream().noneMatch(w -> w.contains("未维护工时定额")), "有定额无警告");
    }

    @Test
    void planEndDatePastBlocked() {
        MdmItem product = mkMake("创建产品三");
        MdmItem child = mkBuy("创建子件三");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        ServiceException e = assertThrows(ServiceException.class, () ->
                moService.create(head(product, "5", LocalDate.now().minusDays(1))));
        assertEquals(422, e.getCode(), "C-4.5-06 完工日期 L1");
        assertTrue(e.getMessage().contains("早于当前日期"), e.getMessage());
    }

    @Test
    void zeroQtyBlocked() {
        MdmItem product = mkMake("创建产品四");
        MdmItem child = mkBuy("创建子件四");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        ServiceException e = assertThrows(ServiceException.class, () ->
                moService.create(head(product, "0", LocalDate.now().plusDays(7))));
        assertEquals(422, e.getCode(), "数量为零阻断");
    }

    @Test
    void uniqueInFlightBlocksSecondCreate() {
        MdmItem product = mkMake("创建产品五");
        MdmItem child = mkBuy("创建子件五");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        moService.create(head(product, "5", LocalDate.now().plusDays(7)));
        ServiceException e = assertThrows(ServiceException.class, () ->
                moService.create(head(product, "5", LocalDate.now().plusDays(7))));
        assertEquals(422, e.getCode(), "唯一在途阻断");
        assertTrue(e.getMessage().contains("在途工单"), e.getMessage());
    }

    @Test
    void missingPublishedBomBlocked() {
        MdmItem product = mkMake("创建产品六");
        ServiceException e = assertThrows(ServiceException.class, () ->
                moService.create(head(product, "5", LocalDate.now().plusDays(7))));
        assertEquals(422, e.getCode(), "无已发布 BOM 阻断");
        assertTrue(e.getMessage().contains("BOM"), e.getMessage());
    }

    @Test
    void inactiveWorkCenterBlocked() {
        MdmItem product = mkMake("创建产品七");
        MdmItem child = mkBuy("创建子件七");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        String wcCode = MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        // 停用工作中心
        workCenterDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, wcCode)
                .set(MrpWorkCenter::getStatus, MrpWorkCenter.ST_INACTIVE));
        ServiceException e = assertThrows(ServiceException.class, () ->
                moService.create(head(product, "5", LocalDate.now().plusDays(7))));
        assertEquals(422, e.getCode(), "WC 停用阻断分配");
        assertTrue(e.getMessage().contains("停用"), e.getMessage());
    }

    @Test
    void overDeliveryBeyondFivePercentBlocked() {
        MdmItem product = mkMake("创建产品八");
        MdmItem child = mkBuy("创建子件八");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.mkSo(jdbc, product.getItemCode(), "100", LocalDate.now().plusDays(10));
        // 超 5% 上限（100×1.05=105）→ 阻断
        ServiceException e = assertThrows(ServiceException.class, () ->
                moService.create(head(product, "110", LocalDate.now().plusDays(7))));
        assertEquals(422, e.getCode(), "BR-4.5-15 超交阻断");
        assertTrue(e.getMessage().contains("超交"), e.getMessage());
    }

    // ---------- 3.2 PMO 入口三场景 ----------

    @Test
    void pmoCreateWritesBackAndIsIdempotent() {
        MdmItem product = mkMake("PMO产品一");
        MdmItem child = mkBuy("PMO子件一");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        String sugId = MoSeed.mkPmoSuggestion(jdbc, product.getItemCode(), "PMO-20261010-901", "12");

        Map<String, Object> out = moService.createFromPmo(sugId, null);
        MrpMo mo = moOf(out);
        assertEquals("PMO-20261010-901", mo.getPlannedMoNo(), "来源 PMO 留痕");
        assertEquals(0, mo.getQty().compareTo(new BigDecimal("12")), "PMO 建议量带出");
        // 回写幂等（D6）
        MrpSuggestion s = suggestionDao.selectById(sugId);
        assertEquals(mo.getMoNo(), s.getMoNo(), "MO_NO 回写建议行");
        List<Map<String, Object>> cands = moService.candidatePmos();
        assertTrue(cands.stream().noneMatch(c -> sugId.equals(c.get("id"))), "已关联 PMO 不再可选");
        // 重复选入 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> moService.createFromPmo(sugId, null));
        assertEquals(422, e.getCode(), "重复建单阻断");
        assertTrue(e.getMessage().contains("已关联"), e.getMessage());
    }

    @Test
    void candidatePmosOnlyReturnsUnlinkedConverted() {
        MdmItem product = mkMake("PMO产品二");
        MdmItem child = mkBuy("PMO子件二");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        String sugId = MoSeed.mkPmoSuggestion(jdbc, product.getItemCode(), "PMO-20261010-902", "8");
        List<Map<String, Object>> cands = moService.candidatePmos();
        assertTrue(cands.stream().anyMatch(c -> sugId.equals(c.get("id"))), "未关联 CONVERTED PMO 可选");
        Map<String, Object> hit = cands.stream().filter(c -> sugId.equals(c.get("id"))).findFirst().orElseThrow();
        assertEquals("PMO-20261010-902", hit.get("pmoNo"), "PMO 单号字段");
        assertEquals(product.getItemCode(), hit.get("itemCode"), "产品编码带出");
        assertNotNull(hit.get("qty"), "建议量带出");
    }

    @Test
    void nonPmoSuggestionRejected() {
        MdmItem product = mkMake("PMO产品三");
        ServiceException e = assertThrows(ServiceException.class,
                () -> moService.createFromPmo("sug-not-exists", null));
        assertEquals(404, e.getCode(), "不存在的建议 404");
    }

    // ---------- 3.3 快照隔离 ----------

    @Test
    void bomSnapshotIsolatedFromLaterBomChange() {
        MdmItem product = mkMake("快照产品");
        MdmItem child = mkBuy("快照子件");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0.05");
        Map<String, Object> out = moService.create(head(product, "10", LocalDate.now().plusDays(7)));
        MrpMo mo = moOf(out);

        // 源 BOM 发布新版本（用量 2 → 9）
        MoSeed.publishBom(bomService, jdbc, product, child, "9", "0");
        // 快照行不变（BR-4.5-16）
        List<MrpMoBom> lines = moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, mo.getId()));
        assertEquals(1, lines.size(), "行数不变");
        assertEquals(0, lines.get(0).getUnitQty().compareTo(new BigDecimal("2")),
                "快照用量保持创建时值（2），不随源 BOM 变更");
    }

    // ---------- 超交边界：5% 内放行 ----------

    @Test
    void overDeliveryWithinFivePercentAllowed() {
        MdmItem product = mkMake("创建产品九");
        MdmItem child = mkBuy("创建子件九");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.mkSo(jdbc, product.getItemCode(), "100", LocalDate.now().plusDays(10));
        Map<String, Object> out = moService.create(head(product, "105", LocalDate.now().plusDays(7)));
        assertNotNull(moOf(out), "100×1.05=105 边界内放行");
    }
}
