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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOM 创建与复制（change add-bom-management，spec bom-management，任务 2.2/2.3）：
 * 新建成功 / 父项停用 422 / 用量 0 阻断 / 停用子项阻断 / 损耗率 120% 阻断 /
 * 复制字段一致与 copy_from_id 留痕 / 源为已废止版本复制仍生成独立草稿。
 */
@SpringBootTest
class BomCreateDbTest {

    @Autowired
    private BomService bomService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem parent;
    private MdmItem child;

    @BeforeEach
    void seed() {
        cleanup();
        BomTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
        parent = mkItem("BOM测试父项独龙甲");
        child = mkItem("BOM测试子项青鸾乙");
    }

    @AfterEach
    void tearDown() {
        BomTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM测试%' " +
                "OR ITEM_NAME LIKE 'BOM替代测试%' OR ITEM_NAME LIKE 'BOM生命周期测试%' " +
                "OR ITEM_NAME LIKE 'BOM扫描测试%')");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE NOT IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item)");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom)");
        jdbc.update("DELETE FROM erp_mrp_bom_substitute WHERE BOM_ITEM_ID NOT IN " +
                "(SELECT ID FROM erp_mrp_bom_item)");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM测试%'");
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

    private MrpBomItem line(MdmItem item, String qty, String lossRate) {
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(item.getItemCode());
        row.setQty(new BigDecimal(qty));
        row.setLossRate(new BigDecimal(lossRate));
        return row;
    }

    private MrpBom head(MdmItem parentItem) {
        MrpBom h = new MrpBom();
        h.setParentItemCode(parentItem.getItemCode());
        return h;
    }

    // ---------- 2.2 创建五场景 ----------

    @Test
    void createSuccessWithLineSnapshot() {
        MrpBom saved = bomService.create(head(parent), List.of(line(child, "2", "5")));
        assertEquals(MrpBom.ST_DRAFT, saved.getStatus(), "新建即草稿");
        assertEquals(1, saved.getVersionMajor(), "首版主版本");
        assertEquals(0, saved.getVersionMinor(), "首版次版本");
        assertEquals("1.0", saved.getVersionLabel(), "版本号展示");

        Map<String, Object> detail = bomService.detail(saved.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertEquals(1, items.size(), "行数");
        assertEquals(child.getItemCode(), items.get(0).get("itemCode"), "子项编码");
        assertEquals("PC", items.get(0).get("uom"), "单位以 MDM 快照");
        assertEquals(child.getItemName(), items.get(0).get("itemName"), "名称以 MDM 快照");
    }

    @Test
    void parentDisabledRejected() {
        itemService.disable(parent.getId(), "BOM测试停用父项");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> bomService.create(head(parent), List.of(line(child, "1", "0"))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("父项"), "提示父项不可用：" + ex.getMessage());
    }

    @Test
    void qtyZeroRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> bomService.create(head(parent), List.of(line(child, "0", "0"))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("用量"), "提示用量：" + ex.getMessage());
    }

    @Test
    void childDisabledRejected() {
        itemService.disable(child.getId(), "BOM测试停用子项");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> bomService.create(head(parent), List.of(line(child, "1", "0"))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("停用"), "提示子项停用：" + ex.getMessage());
    }

    @Test
    void lossRateOutOfRangeRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> bomService.create(head(parent), List.of(line(child, "1", "120"))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("损耗率"), "提示损耗率：" + ex.getMessage());
    }

    // ---------- 2.3 复制两场景 ----------

    @Test
    void copyClonesHeaderLinesWithTrace() {
        MrpBom src = bomService.create(head(parent), List.of(line(child, "3", "5")));
        // 从已发布版本复制（在途草稿不可作复制目标：同父项唯一在途，任务 4.3）
        jdbc.update("UPDATE erp_mrp_bom SET STATUS = 'PUBLISHED' WHERE ID = ?", src.getId());
        MrpBom copied = bomService.copy(src.getId());

        assertEquals(MrpBom.ST_DRAFT, copied.getStatus(), "复制生成草稿");
        assertEquals(parent.getItemCode(), copied.getParentItemCode(), "父项一致");
        assertEquals(src.getId(), copied.getCopyFromId(), "copy_from_id 留痕");
        assertEquals(1, copied.getVersionMajor(), "复制版本主");
        assertEquals(1, copied.getVersionMinor(), "复制版本次 +1（V1.1）");

        Map<String, Object> detail = bomService.detail(copied.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertEquals(1, items.size(), "行克隆");
        assertEquals(0, new BigDecimal("3").compareTo(new BigDecimal(items.get(0).get("qty").toString())),
                "用量一致");
        // 源版本数据不受复制影响
        Map<String, Object> srcDetail = bomService.detail(src.getId());
        assertNotNull(srcDetail.get("items"), "源版本仍可查");
    }

    @Test
    void copyFromObsoleteSourceStillCreatesDraft() {
        MrpBom src = bomService.create(head(parent), List.of(line(child, "1", "0")));
        jdbc.update("UPDATE erp_mrp_bom SET STATUS='OBSOLETE' WHERE ID = ?", src.getId());

        MrpBom copied = bomService.copy(src.getId());
        assertEquals(MrpBom.ST_DRAFT, copied.getStatus(), "已废止源仍复制出独立草稿");
        assertEquals(src.getId(), copied.getCopyFromId(), "copy_from_id 留痕");
        Map<String, Object> detail = bomService.detail(copied.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertEquals(1, items.size(), "行照常克隆");
    }
}
