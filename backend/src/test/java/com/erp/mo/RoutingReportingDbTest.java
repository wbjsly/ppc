package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpRoutingOpDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * 路线行两字段与快照带入库测（task 3.1，routing-management MODIFIED 兑现）：
 * 良率 0/101 保存 422、缺省 98（spec「标准良率越界阻断」）、快照带入 95 与关键标记
 * （spec「良率与关键标记随快照带入」）、路线改版不影响在制、下游查询返回两字段。
 */
@SpringBootTest
class RoutingReportingDbTest {

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
    private MrpRoutingOpDao routingOpDao;
    @Autowired
    private MrpMoOpDao moOpDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MdmItem child;
    private String opCode;
    private String wcCode;

    private static final LocalDate START = LocalDate.of(2028, 12, 4);
    private static final LocalDate END = LocalDate.of(2029, 1, 1);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("routing-rpt", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "良率快照产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "良率快照子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");

        int n = (int) (System.nanoTime() % 100000);
        opCode = "YLDOP-" + n;
        wcCode = "YLDWC-" + n;
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("良率快照工序");
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("良率快照中心");
        workCenterService.create(wc);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_mrp_mo_op WHERE MO_ID IN "
                + "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE=?)", product.getItemCode());
        jdbc.update("DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE=?", product.getItemCode());
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE=?", product.getItemCode());
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE=?", opCode);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE=?", wcCode);
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE=?", opCode);
        MoSeed.cleanup(jdbc);
    }

    /** 装配单行路线（可指定良率与关键标记），返回 draft id 并发布 */
    private String publishRoute(BigDecimal yield, String keyFlag) {
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        row.setLeadTime(BigDecimal.ONE);
        row.setStandardYield(yield);
        row.setKeyOpFlag(keyFlag);
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRouting draft = routingService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='PENDING' WHERE ID=?", draft.getId());
        routingService.publishApproved(draft.getId());
        return draft.getId();
    }

    @Test
    void yieldValidationAndDefault() {
        // 越界 0 → 422
        ServiceException zero = assertThrows(ServiceException.class,
                () -> publishRoute(BigDecimal.ZERO, "0"));
        assertEquals(422, zero.getCode(), "良率 0 → 422：" + zero.getMessage());
        assertTrue(zero.getMessage().contains("标准良率"), "提示标准良率：" + zero.getMessage());
        // 越界 101 → 422
        ServiceException over = assertThrows(ServiceException.class,
                () -> publishRoute(new BigDecimal("101"), "0"));
        assertEquals(422, over.getCode(), "良率 101 → 422：" + over.getMessage());

        // 缺省 98（不传值）
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRouting draft = routingService.create(head, List.of(row));
        MrpRoutingOp saved = routingOpDao.selectOne(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, draft.getId()));
        assertEquals(0, saved.getStandardYield().compareTo(new BigDecimal("98")),
                "缺省 98：" + saved.getStandardYield());
        assertEquals("0", saved.getKeyOpFlag(), "关键标记缺省 0");
    }

    @Test
    void snapshotCarriesYieldAndKeyFlagAndRouteChangeIsolated() {
        // 发布良率 95 + 关键工序的路线
        publishRoute(new BigDecimal("95"), "1");

        // 创建工单 → 快照带入
        MrpMo mo = MoSeed.releaseMo(moService, product, "10", START, END);
        MrpMoOp opRow = moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId()).orderByAsc(MrpMoOp::getOpSeq).last("LIMIT 1"));
        assertNotNull(opRow, "快照行存在");
        assertEquals(0, opRow.getStandardYield().compareTo(new BigDecimal("95")),
                "快照带入标准良率 95：" + opRow.getStandardYield());
        assertEquals("1", opRow.getKeyOpFlag(), "快照带入关键标记");

        // 路线改版：直接改已发布行模拟改版（在制工单快照不受影响）
        jdbc.update("UPDATE erp_mrp_routing_op SET STANDARD_YIELD=90 WHERE ROUTING_ID IN "
                + "(SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE=?)", product.getItemCode());
        // 路线行已变 90（改版生效）
        MrpRoutingOp changed = routingOpDao.selectOne(new LambdaQueryWrapper<MrpRoutingOp>()
                .in(MrpRoutingOp::getRoutingId,
                        jdbc.queryForList("SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE=?",
                                String.class, product.getItemCode()))
                .last("LIMIT 1"));
        assertEquals(0, changed.getStandardYield().compareTo(new BigDecimal("90")),
                "路线行改版为 90");
        // 在制（mo）仍是 95——改版不影响在制（快照隔离，spec 场景「路线改版不影响在制」）
        MrpMoOp opRowFresh = moOpDao.selectById(opRow.getId());
        assertEquals(0, opRowFresh.getStandardYield().compareTo(new BigDecimal("95")),
                "在制工单不受路线改版影响（快照隔离）");
        assertEquals("1", opRowFresh.getKeyOpFlag(), "在制关键标记保持");
    }

    @Test
    void downstreamQueryCarriesBothFields() {
        publishRoute(new BigDecimal("97"), "1");
        // 下游消费契约：按产品查询已发布路线（detail 接口的 ops 序列化）
        Map<String, Object> detail = routingService.detail(
                jdbc.queryForObject("SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE=?",
                        String.class, product.getItemCode()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) detail.get("ops");
        assertNotNull(ops, "ops 存在");
        Map<String, Object> first = ops.get(0);
        assertNotNull(first.get("standardYield"), "下游返回 standardYield");
        assertEquals(0, new BigDecimal(String.valueOf(first.get("standardYield")))
                .compareTo(new BigDecimal("97")), "值 = 97");
        assertEquals("1", String.valueOf(first.get("keyOpFlag")), "关键标记返回 1");
    }
}
