package com.erp.mdm;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.service.mdm.MdmItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物料 ABC 分类字段（change add-inventory-reports，spec item-master-creation MODIFIED，任务 2.3）：
 * 非法值 422 / 可空保存 NULL（未分类）/ 编辑同步回写与校验。
 */
@SpringBootTest
class MdmItemAbcDbTest {

    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'ABC分类测试%'");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'ABC分类测试%'");
    }

    private MdmItem newItem(String suffix, String abc) {
        MdmItem item = new MdmItem();
        item.setItemName("ABC分类测试物料-" + suffix);
        item.setCategoryCode(categoryCode);
        item.setBaseUnit("PC");
        item.setMaterialGroup("STRUCT");
        item.setPurchaseType("BUY");
        item.setStorageCondition("NORMAL");
        item.setBatchFlag("0");
        item.setStatus("1");
        item.setAbcClass(abc);
        return item;
    }

    @Test
    void validAbcSavedAndNormalized() {
        MdmItem saved = itemService.create(newItem("OK", " A "), false);
        assertEquals("A", saved.getAbcClass(), "保存前 trim 归一化");
        assertEquals("A", itemService.getById(saved.getId()).getAbcClass(), "落库核验");
    }

    @Test
    void invalidAbcRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> itemService.create(newItem("BAD", "D"), false));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("ABC"), ex.getMessage());
        // 小写不放行（取值域严格 A/B/C）
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> itemService.create(newItem("LOW", "a"), false));
        assertEquals(422, ex2.getCode());
    }

    @Test
    void nullAbcAllowed() {
        MdmItem saved = itemService.create(newItem("NULL", null), false);
        assertNull(saved.getAbcClass(), "NULL=未分类");
        // 空串归一化为 NULL
        MdmItem blank = itemService.create(newItem("BLANK", ""), false);
        assertNull(blank.getAbcClass(), "空串保存为 NULL");
    }

    @Test
    void updateSyncsAbc() {
        MdmItem saved = itemService.create(newItem("UPD", null), false);
        MdmItem edit = new MdmItem();
        edit.setId(saved.getId());
        edit.setItemName(saved.getItemName());
        edit.setCategoryCode(saved.getCategoryCode());
        edit.setBaseUnit(saved.getBaseUnit());
        edit.setMaterialGroup(saved.getMaterialGroup());
        edit.setPurchaseType(saved.getPurchaseType());
        edit.setStorageCondition(saved.getStorageCondition());
        edit.setBatchFlag(saved.getBatchFlag());
        edit.setAbcClass("B");
        edit.setChangeReason("ABC 分类补录");
        edit.setVerNo(saved.getVerNo());
        MdmItem updated = itemService.update(edit);
        assertEquals("B", updated.getAbcClass());
        assertEquals("B", itemService.getById(saved.getId()).getAbcClass(), "编辑同步回写");

        // 编辑非法值 422
        MdmItem bad = new MdmItem();
        bad.setId(saved.getId());
        bad.setItemName(saved.getItemName());
        bad.setCategoryCode(saved.getCategoryCode());
        bad.setBaseUnit(saved.getBaseUnit());
        bad.setMaterialGroup(saved.getMaterialGroup());
        bad.setPurchaseType(saved.getPurchaseType());
        bad.setStorageCondition(saved.getStorageCondition());
        bad.setBatchFlag(saved.getBatchFlag());
        bad.setAbcClass("Z");
        bad.setChangeReason("非法值测试");
        bad.setVerNo(itemService.getById(saved.getId()).getVerNo());
        ServiceException ex = assertThrows(ServiceException.class, () -> itemService.update(bad));
        assertEquals(422, ex.getCode());
    }
}
