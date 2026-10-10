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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线版本与状态机（change add-routing-management，任务 4.2/4.3/4.4 + 6.2 留痕）：
 * V1.0→V1.1→V2.0 分配、唯一键冲突自动递增、唯一在途阻断、发布旧版 REVISED 无双发布、
 * 仅已发布可变更/缺原因 422、克隆且源头不动、版本历史留痕字段。
 */
@SpringBootTest
class RoutingLifecycleDbTest {

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
    private String opCode;
    private String wcCode;

    @BeforeEach
    void seed() {
        cleanup();
        RoutingTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
        product = mkItem("工艺测试生命周期庚辛");
        opCode = OP + "-L01";
        wcCode = WC + "-L01";
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("生命周期测试工序");
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("生命周期测试中心");
        workCenterService.create(wc);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);
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

    private MrpRoutingOp row() {
        MrpRoutingOp op = new MrpRoutingOp();
        op.setOpCode(opCode);
        op.setWcCode(wcCode);
        op.setLeadTime(BigDecimal.ONE);
        return op;
    }

    private MrpRouting head() {
        MrpRouting h = new MrpRouting();
        h.setItemCode(product.getItemCode());
        return h;
    }

    /** 测试辅助：直接改状态（绕过 CAS，模拟审批推进到某态） */
    private void promote(String routingId, String status) {
        jdbc.update("UPDATE erp_mrp_routing SET STATUS = ? WHERE ID = ?", status, routingId);
    }

    private MrpRouting promoteAndPublish(MrpRouting draft) {
        promote(draft.getId(), MrpRouting.ST_PENDING);
        routingService.publishApproved(draft.getId());
        return draft;
    }

    private String statusOf(String routingId) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_mrp_routing WHERE ID = ?", String.class, routingId);
    }

    private int publishedCount(String itemCode) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_mrp_routing WHERE ITEM_CODE = ? AND STATUS = 'PUBLISHED'",
                Integer.class, itemCode);
    }

    private int opCount(String routingId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_mrp_routing_op WHERE ROUTING_ID = ?",
                Integer.class, routingId);
    }

    // ---------- 任务 4.2：版本号分配 ----------

    @Test
    void versionAllocationIncrementAndMajorUpgrade() {
        // 客户端注入版本字段被忽略（服务端权威，字段只读）
        MrpRouting headInjected = head();
        headInjected.setVersionMajor(99);
        headInjected.setVersionMinor(99);
        MrpRouting v10 = routingService.create(headInjected, List.of(row()));
        assertEquals(1, v10.getVersionMajor(), "首版 V1.0（客户端注入被忽略）");
        assertEquals(0, v10.getVersionMinor());
        assertEquals("1.0", v10.getVersionLabel());

        promoteAndPublish(v10);
        MrpRouting v11 = routingService.change(v10.getId(), "第一次变更", false);
        assertEquals(1, v11.getVersionMajor(), "V1.1 主不变");
        assertEquals(1, v11.getVersionMinor(), "V1.1 次 +1");

        promoteAndPublish(v11);
        MrpRouting v20 = routingService.change(v11.getId(), "第二次变更（大改）", true);
        assertEquals(2, v20.getVersionMajor(), "V2.0 主 +1");
        assertEquals(0, v20.getVersionMinor(), "V2.0 次归零");
    }

    @Test
    void versionConflictAutoIncrementOnHiddenKey() {
        // 逻辑删除行占唯一键（对 nextVersion 不可见）→ 冲突自动递增到 V1.1
        MrpRouting v10 = routingService.create(head(), List.of(row()));
        jdbc.update("UPDATE erp_mrp_routing SET DEL_FLAG = '1' WHERE ID = ?", v10.getId());

        MrpRouting retried = routingService.create(head(), List.of(row()));
        assertEquals(1, retried.getVersionMajor(), "重试后主版本");
        assertEquals(1, retried.getVersionMinor(), "冲突自动递增至次版本 1");
    }

    // ---------- 任务 4.3：唯一在途 / 唯一已发布 ----------

    @Test
    void inFlightBlocksNewCreateAndChange() {
        MrpRouting v10 = routingService.create(head(), List.of(row()));
        promoteAndPublish(v10);
        MrpRouting draft = routingService.change(v10.getId(), "在途测试", false);
        assertEquals(MrpRouting.ST_DRAFT, draft.getStatus());

        ServiceException exCreate = assertThrows(ServiceException.class,
                () -> routingService.create(head(), List.of(row())));
        assertEquals(422, exCreate.getCode());
        assertTrue(exCreate.getMessage().contains("在途"), "提示在途：" + exCreate.getMessage());

        ServiceException exChange = assertThrows(ServiceException.class,
                () -> routingService.change(v10.getId(), "再次变更", false));
        assertEquals(422, exChange.getCode());
        assertTrue(exChange.getMessage().contains("在途"), "变更同样被在途阻断");
    }

    @Test
    void publishRevisesOldAndKeepsSinglePublished() {
        MrpRouting v10 = routingService.create(head(), List.of(row()));
        promoteAndPublish(v10);
        assertEquals(MrpRouting.ST_PUBLISHED, statusOf(v10.getId()), "首次发布生效");
        assertEquals(1, publishedCount(product.getItemCode()), "唯一已发布");
        assertNotNull(jdbc.queryForObject("SELECT PUBLISH_BY FROM erp_mrp_routing WHERE ID = ?",
                String.class, v10.getId()), "发布人留痕");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_routing WHERE ID = ? AND PUBLISH_AT IS NOT NULL",
                Integer.class, v10.getId()), "发布时间留痕");

        MrpRouting v11 = routingService.change(v10.getId(), "升级变更", false);
        promoteAndPublish(v11);
        assertEquals(MrpRouting.ST_REVISED, statusOf(v10.getId()), "旧已发布自动 REVISED");
        assertEquals(MrpRouting.ST_PUBLISHED, statusOf(v11.getId()), "新版已发布");
        assertEquals(1, publishedCount(product.getItemCode()), "不存在双已发布");
    }

    // ---------- 任务 4.4：变更克隆 ----------

    @Test
    void changeOnlyFromPublishedWithRequiredReason() {
        MrpRouting draft = routingService.create(head(), List.of(row()));

        ServiceException exStatus = assertThrows(ServiceException.class,
                () -> routingService.change(draft.getId(), "原因", false));
        assertEquals(422, exStatus.getCode());
        assertTrue(exStatus.getMessage().contains("已发布"), "提示仅已发布可变更");

        promoteAndPublish(draft);
        ServiceException exReason = assertThrows(ServiceException.class,
                () -> routingService.change(draft.getId(), "   ", false));
        assertEquals(422, exReason.getCode());
        assertTrue(exReason.getMessage().contains("变更原因"), "提示变更原因必填");

        ServiceException exMissing = assertThrows(ServiceException.class,
                () -> routingService.change("no-such-routing-id", "原因", false));
        assertEquals(404, exMissing.getCode());
    }

    @Test
    void changeClonesOpsAndKeepsSourceUntouched() {
        MrpRouting v10 = routingService.create(head(), List.of(row()));
        promoteAndPublish(v10);

        MrpRouting draft = routingService.change(v10.getId(), "结构调整", false);
        // 源版本：状态未变、数据未变、无变更字段
        assertEquals(MrpRouting.ST_PUBLISHED, statusOf(v10.getId()), "源保持已发布");
        assertEquals(1, opCount(v10.getId()), "源行数不变");
        assertNull(jdbc.queryForObject("SELECT CHANGE_REASON FROM erp_mrp_routing WHERE ID = ?",
                String.class, v10.getId()), "源无变更原因");
        // 新草稿：变更链与行克隆
        assertEquals(v10.getId(), draft.getChangeFromId(), "change_from_id 变更链");
        assertEquals("结构调整", draft.getChangeReason(), "变更原因落库");
        assertEquals(1, opCount(draft.getId()), "行克隆到新版本");
        assertEquals(MrpRouting.ST_DRAFT, statusOf(draft.getId()), "新版本为草稿");
    }

    // ---------- 任务 4.4：版本历史留痕字段（FR 降级出口） ----------

    @Test
    void queryExposesVersionHistoryTraceFields() {
        MrpRouting v10 = routingService.create(head(), List.of(row()));
        promoteAndPublish(v10);
        routingService.change(v10.getId(), "接口联调变更", false);

        List<Map<String, Object>> rows = routingService.query(null, product.getItemCode(), null);
        assertEquals(2, rows.size(), "该产品两个版本");
        for (Map<String, Object> row : rows) {
            assertTrue(row.containsKey("changeReason"), "含变更原因字段");
            assertTrue(row.containsKey("changeFromId"), "含来源版本字段");
            assertTrue(row.containsKey("publishBy"), "含发布人字段");
            assertTrue(row.containsKey("publishAt"), "含发布时间字段");
            assertTrue(row.containsKey("rejectReason"), "含驳回意见字段");
            assertTrue(row.containsKey("versionLabel"), "含版本号展示");
        }
        Map<String, Object> published = rows.stream()
                .filter(r -> MrpRouting.ST_PUBLISHED.equals(r.get("status"))).findFirst().orElseThrow();
        assertNotNull(published.get("publishBy"), "已发布行有发布人值");
    }
}
