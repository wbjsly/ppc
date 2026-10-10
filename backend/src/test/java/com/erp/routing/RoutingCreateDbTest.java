package com.erp.routing;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mdm.MdmItemService;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线装配与下游契约（change add-routing-management，任务 3.2/3.3）：
 * 装配成功（op_seq/版本/详情含定额与产能）/ 空路线 422 / 无定额 422（消息含两编码）/
 * 补定额后保存放行；契约查询：未发布返回空、已发布返回有序行 + 四类工时 + 产能三要素。
 */
@SpringBootTest
class RoutingCreateDbTest {

    private static final String OP = "RTOP";
    private static final String WC = "RTWC";

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
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;

    @BeforeEach
    void seed() {
        cleanup();
        RoutingTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
        product = mkItem("工艺测试装配品戊己");
    }

    @AfterEach
    void tearDown() {
        RoutingTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID IN (" +
                "SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工艺测试%'))");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工艺测试%')");
        jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID NOT IN " +
                "(SELECT ID FROM erp_mrp_routing)");
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE LIKE 'RTOP%' OR WC_CODE LIKE 'RTWC%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'RTOP%'");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'RTWC%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工艺测试%'");
    }

    private MdmItem mkItem(String name) {
        MdmItem item = new MdmItem();
        item.setItemName(name);
        item.setCategoryCode(categoryCode);
        item.setBaseUnit("PC");
        item.setMaterialGroup("STRUCT");
        item.setPurchaseType("BUY");
        item.setStorageCondition("NORMAL");
        item.setBatchFlag("0");
        item.setPackingSpec("箱");
        item.setDupNote("工艺路线模块测试物料，非业务重复数据");
        return itemService.create(item, true);
    }

    private MrpOperation mkOp(String code) {
        MrpOperation in = new MrpOperation();
        in.setOpCode(code);
        in.setOpName("测试工序-" + code);
        return operationService.create(in);
    }

    private MrpWorkCenter mkWc(String code) {
        MrpWorkCenter in = new MrpWorkCenter();
        in.setWcCode(code);
        in.setWcName("测试中心-" + code);
        return workCenterService.create(in);
    }

    private MrpOpWcStandard mkStd(String opCode, String wcCode) {
        MrpOpWcStandard in = new MrpOpWcStandard();
        in.setOpCode(opCode);
        in.setWcCode(wcCode);
        in.setSetupHours(new BigDecimal("0.5"));
        in.setRunHours(new BigDecimal("1.5"));
        in.setWaitHours(new BigDecimal("0.2"));
        in.setMoveHours(BigDecimal.ZERO);
        return standardService.create(in);
    }

    private MrpRoutingOp row(String opCode, String wcCode) {
        MrpRoutingOp op = new MrpRoutingOp();
        op.setOpCode(opCode);
        op.setWcCode(wcCode);
        op.setLeadTime(new BigDecimal("0.5"));
        return op;
    }

    private MrpRouting head() {
        MrpRouting h = new MrpRouting();
        h.setItemCode(product.getItemCode());
        return h;
    }

    // ---------- 3.2 装配卡控四场景 ----------

    @Test
    void createSuccessWithSeqAndDetail() {
        mkOp(OP + "-D01");
        mkWc(WC + "-D01");
        mkStd(OP + "-D01", WC + "-D01");
        mkOp(OP + "-D02");
        mkWc(WC + "-D02");
        mkStd(OP + "-D02", WC + "-D02");

        MrpRouting saved = routingService.create(head(),
                List.of(row(OP + "-D01", WC + "-D01"), row(OP + "-D02", WC + "-D02")));
        assertEquals(MrpRouting.ST_DRAFT, saved.getStatus(), "新建即草稿");
        assertEquals("1.0", saved.getVersionLabel(), "首版 V1.0");

        Map<String, Object> detail = routingService.detail(saved.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) detail.get("ops");
        assertEquals(2, ops.size(), "两道工序");
        assertEquals(10, ((Number) ops.get(0).get("opSeq")).intValue(), "首行 op_seq=10");
        assertEquals(20, ((Number) ops.get(1).get("opSeq")).intValue(), "次行 op_seq=20");
        assertNotNull(ops.get(0).get("standard"), "详情带出定额");
        assertNotNull(ops.get(0).get("workCenter"), "详情带出工作中心");
    }

    @Test
    void emptyOpsBlocked() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> routingService.create(head(), List.of()));
        assertEquals(422, e.getCode(), "空路线 422");
        assertTrue(e.getMessage().contains("至少需要一道工序"), "提示空路线");
    }

    @Test
    void missingStandardBlocked() {
        mkOp(OP + "-D03");
        mkWc(WC + "-D03");
        ServiceException e = assertThrows(ServiceException.class,
                () -> routingService.create(head(), List.of(row(OP + "-D03", WC + "-D03"))));
        assertEquals(422, e.getCode(), "无定额 422");
        assertTrue(e.getMessage().contains(OP + "-D03"), "消息含工序编码");
        assertTrue(e.getMessage().contains(WC + "-D03"), "消息含工作中心编码");
        assertTrue(e.getMessage().contains("5.2.3"), "指引到标准工时页");
    }

    @Test
    void standardAddedThenSavePasses() {
        mkOp(OP + "-D04");
        mkWc(WC + "-D04");
        ServiceException e = assertThrows(ServiceException.class,
                () -> routingService.create(head(), List.of(row(OP + "-D04", WC + "-D04"))));
        assertEquals(422, e.getCode(), "先阻断");

        // 补定额后放行（矩阵先行的依赖序）
        mkStd(OP + "-D04", WC + "-D04");
        MrpRouting saved = routingService.create(head(), List.of(row(OP + "-D04", WC + "-D04")));
        assertEquals(MrpRouting.ST_DRAFT, saved.getStatus(), "补定额后装配成功");
    }

    // ---------- 3.3 下游契约两场景 ----------

    @Test
    void contractReturnsEmptyWhenNotPublished() {
        Map<String, Object> out = routingService.publishedRoute(product.getItemCode());
        assertTrue(out.isEmpty(), "未发布返回空（不泄露草稿）");
    }

    @Test
    void contractReturnsPublishedRouteWithHoursAndCapacity() {
        mkOp(OP + "-E01");
        MrpWorkCenter wc = mkWc(WC + "-E01");
        mkStd(OP + "-E01", WC + "-E01");
        MrpRouting routing = routingService.create(head(), List.of(row(OP + "-E01", WC + "-E01")));
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='PUBLISHED' WHERE ID=?", routing.getId());

        Map<String, Object> out = routingService.publishedRoute(product.getItemCode());
        assertEquals(routing.getId(), out.get("id"), "取到已发布版本");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ops = (List<Map<String, Object>>) out.get("ops");
        assertEquals(1, ops.size(), "一道有序工序");
        @SuppressWarnings("unchecked")
        Map<String, Object> std = (Map<String, Object>) ops.get(0).get("standard");
        assertNotNull(std, "带出四类工时所在定额行");
        assertEquals(0, new BigDecimal(std.get("runHours").toString())
                        .compareTo(new BigDecimal("1.5")),
                "标准工时 1.5");
        @SuppressWarnings("unchecked")
        Map<String, Object> w = (Map<String, Object>) ops.get(0).get("workCenter");
        assertNotNull(w, "带出工作中心");
        assertTrue(w.containsKey("calHours") && w.containsKey("equipAvail")
                && w.containsKey("laborAvail"), "产能三要素可查（公式4分母）");
        assertEquals(wc.getWcCode(), w.get("wcCode"), "工作中心编码一致");
    }
}
