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
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线权限矩阵（change add-routing-management，spec routing-management「服务层角色权限」，任务 5.3）：
 * ENG 调废止 403 / MGR 废止成功 / 未认证 401 / 非工艺角色维护 403。
 */
@SpringBootTest
class RoutingPermissionDbTest {

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
        product = mkItem("工艺测试权限品子丑");
    }

    @AfterEach
    void tearDown() {
        RoutingTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID = a.ID " +
                "WHERE a.BIZ_TYPE = 'RoutingPublish'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'RoutingPublish'");
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

    private MrpRouting createPublished() {
        MrpOperation op = new MrpOperation();
        op.setOpCode("RTOP-X01");
        op.setOpName("权限测试工序");
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode("RTWC-X01");
        wc.setWcName("权限测试中心");
        workCenterService.create(wc);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode("RTOP-X01");
        std.setWcCode("RTWC-X01");
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);

        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode("RTOP-X01");
        row.setWcCode("RTWC-X01");
        row.setLeadTime(BigDecimal.ONE);
        MrpRouting draft = routingService.create(head, List.of(row));
        // 直接置为已发布（废止只看业务状态，本用例测的是角色门）
        jdbc.update("UPDATE erp_mrp_routing SET STATUS = 'PUBLISHED' WHERE ID = ?", draft.getId());
        return draft;
    }

    @Test
    void engineerCannotObsolete() {
        MrpRouting published = createPublished();
        RoutingTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> routingService.obsolete(published.getId()));
        assertEquals(403, ex.getCode(), "工程师废止 403");
        assertTrue(ex.getMessage().contains("无权限"), "提示无权限");
    }

    @Test
    void managerCanObsolete() {
        MrpRouting published = createPublished();
        RoutingTestAuth.login("mgr-tester", "ROLE_PROCESS_MGR");
        routingService.obsolete(published.getId());
        String status = jdbc.queryForObject("SELECT STATUS FROM erp_mrp_routing WHERE ID = ?",
                String.class, published.getId());
        assertEquals(MrpRouting.ST_OBSOLETE, status, "主管废止成功");
    }

    @Test
    void unauthenticatedRejectedWith401() {
        SecurityContextHolder.clearContext();
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode("RTOP-X01");
        row.setWcCode("RTWC-X01");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> routingService.create(head, List.of(row)));
        assertEquals(401, ex.getCode(), "未认证 401");
        assertTrue(ex.getMessage().contains("登录"), "提示需要登录");
    }

    @Test
    void nonProcessRoleRejectedWith403() {
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode("RTOP-X01");
        row.setWcCode("RTWC-X01");
        RoutingTestAuth.login("sales-user", "ROLE_SALES");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> routingService.create(head, List.of(row)));
        assertEquals(403, ex.getCode(), "非工艺角色 403");
    }
}
