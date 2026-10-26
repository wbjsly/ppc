package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 返工路线库测（task 3.1，routing delta：ROUTE_TYPE 装配/唯一在途分池/发布池内置旧/按类型取已发布）。
 */
@SpringBootTest
class RoutingReworkDbTest {

    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MdmItem child;
    private String opCode;
    private String wcCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("rw-routing", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "返工路线产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "返工路线子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");

        int n = (int) (System.nanoTime() % 100000);
        opCode = "RWOP-" + n;
        wcCode = "RWWC-" + n;
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("返工路线工序");
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("返工路线中心");
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
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID IN "
                + "(SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE=?)", product.getItemCode());
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE=?", product.getItemCode());
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE=?", opCode);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE=?", wcCode);
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE=?", opCode);
        MoSeed.cleanup(jdbc);
    }

    private MrpRoutingOp row() {
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        row.setLeadTime(BigDecimal.ONE);
        row.setStandardYield(new BigDecimal("98"));
        row.setKeyOpFlag("0");
        return row;
    }

    private MrpRouting draft(String routeType) {
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        head.setRouteType(routeType);
        return routingService.create(head, List.of(row()));
    }

    /** 直推 PENDING 后发布（submit 挂审批，测试绕行既有先例） */
    private String publish(MrpRouting r) {
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='PENDING' WHERE ID=?", r.getId());
        routingService.publishApproved(r.getId());
        return r.getId();
    }

    @Test
    void routeTypePoolingLifecycle() {
        // ① 缺省 NORMAL + REWORK 独立装配（spec「返工路线独立装配」）：
        //    创建 NORMAL 在途后，REWORK 异型仍放行（唯一在途按类型分池）
        MrpRouting normalDraft = draft(null);
        assertEquals("NORMAL", normalDraft.getRouteType(), "缺省 NORMAL");
        MrpRouting reworkDraft = draft("REWORK");
        assertEquals("REWORK", reworkDraft.getRouteType(), "异型在途时 REWORK 放行");

        // ② 同类型在途唯一阻断仍生效（spec「在途唯一阻断」）
        var blockedNormal = assertThrows(ServiceException.class, () -> draft(null));
        assertEquals(422, blockedNormal.getCode(), "NORMAL 在途唯一阻断：" + blockedNormal.getMessage());
        assertTrue(blockedNormal.getMessage().contains("在途"), "阻断提示在途");

        // ③ 类型不可改（saveDraft 422）
        var typeChange = assertThrows(ServiceException.class,
                () -> routingService.saveDraft(reworkDraft.getId(), headWith("NORMAL"), List.of(row())));
        assertEquals(422, typeChange.getCode(), "类型不可改：" + typeChange.getMessage());

        // ④ 两池各自发布（REWORK 发布不置 NORMAL 已发布为 REVISED——池内发布）
        publish(normalDraft);
        publish(reworkDraft);
        Map<String, Object> normalPub = routingService.publishedRoute(product.getItemCode());
        assertEquals("NORMAL", normalPub.get("routeType"), "NORMAL 池已发布仍是 NORMAL");
        Map<String, Object> reworkPub = routingService.publishedRoute(product.getItemCode(), "REWORK");
        assertEquals("REWORK", reworkPub.get("routeType"), "按类型取 REWORK 已发布");
        assertNotEquals(normalPub.get("id"), reworkPub.get("id"), "两池发布版本独立");

        // ⑤ 同池新版发布置旧版 REVISED；异池不受影响
        MrpRouting normalV2 = draft(null);
        publish(normalV2);
        assertEquals("REVISED", jdbc.queryForObject(
                        "SELECT STATUS FROM erp_mrp_routing WHERE ID=?", String.class, normalDraft.getId()),
                "NORMAL 池旧已发布 → REVISED");
        assertEquals("PUBLISHED", jdbc.queryForObject(
                        "SELECT STATUS FROM erp_mrp_routing WHERE ID=?", String.class, reworkDraft.getId()),
                "REWORK 已发布不受 NORMAL 发布影响");

        // ⑥ 下游契约：无 REWORK 已发布时按类型取为空（spec「按类型取返工路线」缺失场景）
        MdmItem p2 = MoSeed.mkItem(itemService, categoryCode, "无返工路线产品", "MAKE");
        assertTrue(routingService.publishedRoute(p2.getItemCode(), "REWORK").isEmpty(),
                "无 REWORK 已发布返回空");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE=?", p2.getItemCode());
    }

    private MrpRouting headWith(String routeType) {
        MrpRouting h = new MrpRouting();
        h.setRouteType(routeType);
        return h;
    }

    @Test
    void invalidRouteTypeRejected() {
        var e = assertThrows(ServiceException.class, () -> draft("MAINTENANCE"));
        assertEquals(422, e.getCode(), "非法类型 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("NORMAL/REWORK"), "提示取值：" + e.getMessage());
    }
}
