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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工序字典 / 工作中心 / 定额矩阵（change add-routing-management，任务 2.2/2.3/2.4）：
 * 新增成功 / 改码 422 / 停用不可装配 422；外协缺供应商 422 / 非法产能 422 /
 * 停用工作中心不可装配 422；组合重复 422 / 负工时 422 / 删除被已发布路线引用 422。
 */
@SpringBootTest
class OperationWorkCenterDbTest {

    private static final String OP = "RTOP";
    private static final String WC = "RTWC";

    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private RoutingService routingService;
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
        product = mkItem("工艺测试产品丙丁");
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
        in.setSkillReq("会操作测试台");
        return operationService.create(in);
    }

    private MrpWorkCenter mkWc(String code) {
        MrpWorkCenter in = new MrpWorkCenter();
        in.setWcCode(code);
        in.setWcName("测试工作中心-" + code);
        in.setWcType(MrpWorkCenter.TYPE_INTERNAL);
        return workCenterService.create(in);
    }

    private MrpOpWcStandard mkStd(String opCode, String wcCode) {
        MrpOpWcStandard in = new MrpOpWcStandard();
        in.setOpCode(opCode);
        in.setWcCode(wcCode);
        in.setSetupHours(new BigDecimal("0.5"));
        in.setRunHours(new BigDecimal("1.25"));
        in.setWaitHours(BigDecimal.ZERO);
        in.setMoveHours(new BigDecimal("0.1"));
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

    // ---------- 2.2 工序字典三场景 ----------

    @Test
    void operationCreateAndQuery() {
        MrpOperation saved = mkOp(OP + "-A01");
        assertEquals("1", saved.getStatus(), "新建即启用");
        List<Map<String, Object>> found = operationService.query(OP + "-A01", null);
        assertEquals(1, found.size(), "关键字命中");
        assertEquals("测试工序-" + OP + "-A01", found.get(0).get("opName"), "名称落库");
    }

    @Test
    void operationCodeImmutable() {
        MrpOperation saved = mkOp(OP + "-A02");
        MrpOperation in = new MrpOperation();
        in.setId(saved.getId());
        in.setOpCode("RTOP-CHANGED");
        in.setOpName("改名可改");
        ServiceException e = assertThrows(ServiceException.class, () -> operationService.update(in));
        assertEquals(422, e.getCode(), "改码 422");
        assertTrue(e.getMessage().contains("不可修改"), "提示编码不可改");
    }

    @Test
    void disabledOperationBlockedInRoute() {
        mkOp(OP + "-A03");
        mkWc(WC + "-A03");
        mkStd(OP + "-A03", WC + "-A03");
        MrpOperation op = operationService.query(OP + "-A03", null).stream()
                .findFirst().map(m -> {
                    MrpOperation o = new MrpOperation();
                    o.setId((String) m.get("id"));
                    return o;
                }).orElseThrow();
        operationService.setStatus(op.getId(), "0");
        ServiceException e = assertThrows(ServiceException.class,
                () -> routingService.create(head(), List.of(row(OP + "-A03", WC + "-A03"))));
        assertEquals(422, e.getCode(), "停用工序装配 422");
        assertTrue(e.getMessage().contains("A03"), "消息含工序编码");
    }

    // ---------- 2.3 工作中心三场景 ----------

    @Test
    void outsourcedMissingSupplierBlocked() {
        MrpWorkCenter in = new MrpWorkCenter();
        in.setWcCode(WC + "-B01");
        in.setWcName("外协测试中心");
        in.setWcType(MrpWorkCenter.TYPE_OUTSOURCED);
        ServiceException e = assertThrows(ServiceException.class, () -> workCenterService.create(in));
        assertEquals(422, e.getCode(), "外协缺供应商 422");
        assertTrue(e.getMessage().contains("供应商"), "提示维护供应商");
    }

    @Test
    void invalidCapacityBlocked() {
        MrpWorkCenter in = new MrpWorkCenter();
        in.setWcCode(WC + "-B02");
        in.setWcName("产能非法中心");
        in.setWcType(MrpWorkCenter.TYPE_INTERNAL);
        in.setEquipAvail(new BigDecimal("150"));
        ServiceException e = assertThrows(ServiceException.class, () -> workCenterService.create(in));
        assertEquals(422, e.getCode(), "设备可用率超界 422");
        assertTrue(e.getMessage().contains("设备可用率"), "提示字段名");

        in.setEquipAvail(new BigDecimal("90"));
        in.setCalHours(BigDecimal.ZERO);
        ServiceException e2 = assertThrows(ServiceException.class, () -> workCenterService.create(in));
        assertEquals(422, e2.getCode(), "日历工时 0 → 422");
    }

    @Test
    void disabledWorkCenterBlockedInRoute() {
        mkOp(OP + "-B03");
        MrpWorkCenter wc = mkWc(WC + "-B03");
        mkStd(OP + "-B03", WC + "-B03");
        workCenterService.setStatus(wc.getId(), "0");
        ServiceException e = assertThrows(ServiceException.class,
                () -> routingService.create(head(), List.of(row(OP + "-B03", WC + "-B03"))));
        assertEquals(422, e.getCode(), "停用工作中心装配 422");
        assertTrue(e.getMessage().contains("B03"), "消息含工作中心编码");
    }

    // ---------- 2.4 定额矩阵三场景 ----------

    @Test
    void duplicatePairBlocked() {
        mkOp(OP + "-C01");
        mkWc(WC + "-C01");
        mkStd(OP + "-C01", WC + "-C01");
        ServiceException e = assertThrows(ServiceException.class,
                () -> mkStd(OP + "-C01", WC + "-C01"));
        assertEquals(422, e.getCode(), "组合重复 422");
        assertTrue(e.getMessage().contains("已存在"), "提示已存在");
    }

    @Test
    void negativeHoursBlocked() {
        mkOp(OP + "-C02");
        mkWc(WC + "-C02");
        MrpOpWcStandard in = new MrpOpWcStandard();
        in.setOpCode(OP + "-C02");
        in.setWcCode(WC + "-C02");
        in.setRunHours(new BigDecimal("-1"));
        ServiceException e = assertThrows(ServiceException.class, () -> standardService.create(in));
        assertEquals(422, e.getCode(), "负工时 422");
        assertTrue(e.getMessage().contains("标准工时"), "提示字段名");
    }

    @Test
    void deleteReferencedByPublishedRoutingBlocked() {
        mkOp(OP + "-C03");
        mkWc(WC + "-C03");
        MrpOpWcStandard std = mkStd(OP + "-C03", WC + "-C03");
        MrpRouting routing = routingService.create(head(), List.of(row(OP + "-C03", WC + "-C03")));
        // 注入为已发布（发布须经审批链，本用例只测删除引用保护，JDBC 直改状态）
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='PUBLISHED' WHERE ID=?", routing.getId());

        ServiceException e = assertThrows(ServiceException.class,
                () -> standardService.delete(std.getId()));
        assertEquals(422, e.getCode(), "被已发布引用 422");
        assertTrue(e.getMessage().contains("已发布路线引用"), "提示被引用");

        // 未发布（草稿）引用不挡删除 → 由路线保存卡控兜底（design D3）
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='DRAFT' WHERE ID=?", routing.getId());
        standardService.delete(std.getId());
    }
}
