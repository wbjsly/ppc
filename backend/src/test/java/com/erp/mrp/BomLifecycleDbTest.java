package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
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
 * BOM 生命周期库测（change add-bom-management）：
 * 任务 3.2 保存内嵌循环校验——成环 L1 阻断且响应含环路径 / 无环正常保存（saveDraft 覆盖路径）。
 * 任务 4.x 版本与状态机场景随后续任务追加到本类。
 */
@SpringBootTest
class BomLifecycleDbTest {

    @Autowired
    private BomService bomService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem itemA;
    private MdmItem itemB;
    private MdmItem itemC;

    @BeforeEach
    void seed() {
        cleanup();
        BomTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
        itemA = mkItem("BOM生命周期测试父项青龙甲");
        itemB = mkItem("BOM生命周期测试子项白虎乙");
        itemC = mkItem("BOM生命周期测试旁支麒麟丙");
    }

    @AfterEach
    void tearDown() {
        BomTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM生命周期测试%' " +
                "OR ITEM_NAME LIKE 'BOM测试%' OR ITEM_NAME LIKE 'BOM替代测试%' OR ITEM_NAME LIKE 'BOM扫描测试%')");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE NOT IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item)");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom)");
        jdbc.update("DELETE FROM erp_mrp_bom_substitute WHERE BOM_ITEM_ID NOT IN " +
                "(SELECT ID FROM erp_mrp_bom_item)");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM生命周期测试%'");
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
        item.setPackingSpec("盒");
        item.setDupNote("BOM 模块测试物料，非业务重复数据");
        return itemService.create(item, true);
    }

    private MrpBomItem line(MdmItem item) {
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(item.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        return row;
    }

    private MrpBom head(MdmItem parentItem) {
        MrpBom h = new MrpBom();
        h.setParentItemCode(parentItem.getItemCode());
        return h;
    }

    /** 测试辅助：直接改状态（绕过 CAS，模拟审批推进到某态） */
    private void promote(String bomId, String status) {
        jdbc.update("UPDATE erp_mrp_bom SET STATUS = ? WHERE ID = ?", status, bomId);
    }

    private MrpBom promoteAndPublish(MrpBom draft) {
        promote(draft.getId(), MrpBom.ST_PENDING);
        bomService.publishApproved(draft.getId());
        return draft;
    }

    private String statusOf(String bomId) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_mrp_bom WHERE ID = ?", String.class, bomId);
    }

    private int publishedCount(String parentItemCode) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_mrp_bom WHERE PARENT_ITEM_CODE = ? AND STATUS = 'PUBLISHED'",
                Integer.class, parentItemCode);
    }

    private int lineCount(String bomId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_mrp_bom_item WHERE BOM_ID = ?",
                Integer.class, bomId);
    }

    // ---------- 任务 3.2：保存内嵌循环校验 ----------

    @Test
    void saveBlockedWithCyclePathInResponse() {
        // A → B 已保存（B 尚无自己的 BOM，无环）
        bomService.create(head(itemA), List.of(line(itemB)));

        // 再保存 B → A：B 的子项 A 的在用 BOM 行含 B → 成环 B→A→B，L1 阻断
        ServiceException ex = assertThrows(ServiceException.class,
                () -> bomService.create(head(itemB), List.of(line(itemA))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("循环引用"), "提示循环引用：" + ex.getMessage());
        assertTrue(ex.getMessage().contains(itemA.getItemCode()), "响应含环路径节点 A");
        assertTrue(ex.getMessage().contains(itemB.getItemCode()), "响应含环路径节点 B");
        assertTrue(ex.getMessage().contains("→"), "响应含环路径");

        // 阻断即回滚：B 的 BOM 未落库
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_bom WHERE PARENT_ITEM_CODE = ?",
                Integer.class, itemB.getItemCode());
        assertEquals(0, count, "被阻断的保存不落库（事务回滚）");
    }

    @Test
    void saveDraftWithoutCycleSucceeds() {
        MrpBom bom = bomService.create(head(itemA), List.of(line(itemB)));

        // 覆盖保存：A → B、C（旁支无环），正常通过
        MrpBom saved = bomService.saveDraft(bom.getId(), head(itemA),
                List.of(line(itemB), line(itemC)));
        assertEquals(MrpBom.ST_DRAFT, saved.getStatus(), "保存后仍为草稿");

        Map<String, Object> detail = bomService.detail(bom.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertEquals(2, items.size(), "覆盖保存两行生效");
    }

    // ---------- 任务 4.2：版本号分配 ----------

    @Test
    void versionAllocationIncrementAndMajorUpgrade() {
        // 客户端注入版本字段被忽略（服务端权威，字段只读）
        MrpBom headInjected = head(itemA);
        headInjected.setVersionMajor(99);
        headInjected.setVersionMinor(99);
        MrpBom v10 = bomService.create(headInjected, List.of(line(itemB)));
        assertEquals(1, v10.getVersionMajor(), "首版 V1.0（客户端注入被忽略）");
        assertEquals(0, v10.getVersionMinor());

        promoteAndPublish(v10);
        // 变更 → 次版本 +1
        MrpBom v11 = bomService.change(v10.getId(), "第一次变更", false);
        assertEquals(1, v11.getVersionMajor(), "V1.1 主不变");
        assertEquals(1, v11.getVersionMinor(), "V1.1 次 +1");

        promoteAndPublish(v11);
        // 升级主版本 → 主 +1 次归零
        MrpBom v20 = bomService.change(v11.getId(), "第二次变更（大改）", true);
        assertEquals(2, v20.getVersionMajor(), "V2.0 主 +1");
        assertEquals(0, v20.getVersionMinor(), "V2.0 次归零");
    }

    @Test
    void versionConflictAutoIncrementOnHiddenKey() {
        // 逻辑删除行占唯一键（对 nextVersion 不可见）→ 冲突自动递增到 V1.1（FR-4.5-1-5）
        MrpBom v10 = bomService.create(head(itemA), List.of(line(itemB)));
        jdbc.update("UPDATE erp_mrp_bom SET DEL_FLAG = '1' WHERE ID = ?", v10.getId());

        MrpBom retried = bomService.create(head(itemA), List.of(line(itemC)));
        assertEquals(1, retried.getVersionMajor(), "重试后主版本");
        assertEquals(1, retried.getVersionMinor(), "冲突自动递增至次版本 1");
    }

    // ---------- 任务 4.3：唯一在途 / 唯一已发布 ----------

    @Test
    void inFlightBlocksNewCreateAndChange() {
        MrpBom v10 = bomService.create(head(itemA), List.of(line(itemB)));
        promoteAndPublish(v10);
        // 发起变更产生在途草稿 V1.1
        MrpBom draft = bomService.change(v10.getId(), "在途测试", false);
        assertEquals(MrpBom.ST_DRAFT, draft.getStatus());

        // 在途存在 → 再创建 / 再变更均 L1 阻断
        ServiceException exCreate = assertThrows(ServiceException.class,
                () -> bomService.create(head(itemA), List.of(line(itemC))));
        assertEquals(422, exCreate.getCode());
        assertTrue(exCreate.getMessage().contains("在途"), "提示在途：" + exCreate.getMessage());

        ServiceException exChange = assertThrows(ServiceException.class,
                () -> bomService.change(v10.getId(), "再次变更", false));
        assertEquals(422, exChange.getCode());
        assertTrue(exChange.getMessage().contains("在途"), "变更同样被在途阻断");
    }

    @Test
    void publishRevisesOldAndKeepsSinglePublished() {
        MrpBom v10 = bomService.create(head(itemA), List.of(line(itemB)));
        promoteAndPublish(v10);
        assertEquals(MrpBom.ST_PUBLISHED, statusOf(v10.getId()), "首次发布生效");
        assertEquals(1, publishedCount(itemA.getItemCode()), "唯一已发布");
        assertNotNull(jdbc.queryForObject("SELECT PUBLISH_BY FROM erp_mrp_bom WHERE ID = ?",
                String.class, v10.getId()), "发布人留痕");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_bom WHERE ID = ? AND PUBLISH_AT IS NOT NULL",
                Integer.class, v10.getId()), "发布时间留痕");

        MrpBom v11 = bomService.change(v10.getId(), "升级变更", false);
        promoteAndPublish(v11);
        assertEquals(MrpBom.ST_REVISED, statusOf(v10.getId()), "旧已发布自动 REVISED（BR-4.5-08）");
        assertEquals(MrpBom.ST_PUBLISHED, statusOf(v11.getId()), "新版已发布");
        assertEquals(1, publishedCount(itemA.getItemCode()), "不存在双已发布");
    }

    // ---------- 任务 4.4：变更克隆 ----------

    @Test
    void changeOnlyFromPublishedWithRequiredReason() {
        MrpBom draft = bomService.create(head(itemA), List.of(line(itemB)));

        // 草稿状态发起变更 → 422
        ServiceException exStatus = assertThrows(ServiceException.class,
                () -> bomService.change(draft.getId(), "原因", false));
        assertEquals(422, exStatus.getCode());
        assertTrue(exStatus.getMessage().contains("已发布"), "提示仅已发布可变更：" + exStatus.getMessage());

        promoteAndPublish(draft);
        // 缺变更原因 → 422
        ServiceException exReason = assertThrows(ServiceException.class,
                () -> bomService.change(draft.getId(), "   ", false));
        assertEquals(422, exReason.getCode());
        assertTrue(exReason.getMessage().contains("变更原因"), "提示变更原因必填");

        // 不存在的版本 → 404
        ServiceException exMissing = assertThrows(ServiceException.class,
                () -> bomService.change("no-such-bom-id", "原因", false));
        assertEquals(404, exMissing.getCode());
    }

    @Test
    void changeClonesLinesAndKeepsSourceUntouched() {
        MrpBom v10 = bomService.create(head(itemA), List.of(line(itemB)));
        promoteAndPublish(v10);

        MrpBom draft = bomService.change(v10.getId(), "结构调整", false);
        // 源版本：状态未变、数据未变、无变更字段
        assertEquals(MrpBom.ST_PUBLISHED, statusOf(v10.getId()), "源保持已发布（BR-4.5-09）");
        assertEquals(1, lineCount(v10.getId()), "源行数不变");
        assertNull(jdbc.queryForObject("SELECT CHANGE_REASON FROM erp_mrp_bom WHERE ID = ?", String.class, v10.getId()),
                "源无变更原因");
        // 新草稿：变更链与行克隆
        assertEquals(v10.getId(), draft.getChangeFromId(), "change_from_id 变更链");
        assertEquals("结构调整", draft.getChangeReason(), "变更原因落库");
        assertEquals(1, lineCount(draft.getId()), "行克隆到新版本");
        assertEquals(MrpBom.ST_DRAFT, statusOf(draft.getId()), "新版本为草稿");
    }

    // ---------- 任务 6.2：版本历史留痕字段 ----------

    @Test
    void queryExposesVersionHistoryTraceFields() {
        MrpBom v10 = bomService.create(head(itemA), List.of(line(itemB)));
        promoteAndPublish(v10);
        MrpBom draft = bomService.change(v10.getId(), "接口联调变更", false);

        List<Map<String, Object>> rows = bomService.query(null, itemA.getItemCode(), null);
        assertEquals(2, rows.size(), "该父项两个版本");
        for (Map<String, Object> row : rows) {
            // FR-7 降级出口：五类留痕字段必须随列表返回
            assertTrue(row.containsKey("changeReason"), "含变更原因字段");
            assertTrue(row.containsKey("changeFromId"), "含来源版本字段");
            assertTrue(row.containsKey("copyFromId"), "含复制来源字段");
            assertTrue(row.containsKey("publishBy"), "含发布人字段");
            assertTrue(row.containsKey("publishAt"), "含发布时间字段");
            assertTrue(row.containsKey("rejectReason"), "含驳回意见字段");
        }
        Map<String, Object> published = rows.stream()
                .filter(r -> MrpBom.ST_PUBLISHED.equals(r.get("status"))).findFirst().orElseThrow();
        assertNotNull(published.get("publishBy"), "已发布行有发布人值");
        Map<String, Object> changed = rows.stream()
                .filter(r -> draft.getId().equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals("接口联调变更", changed.get("changeReason"), "变更原因值");
        assertEquals(v10.getId(), changed.get("changeFromId"), "变更链指向源版本");
    }
}
