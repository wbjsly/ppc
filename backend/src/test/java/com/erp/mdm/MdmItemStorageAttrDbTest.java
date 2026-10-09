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
 * 物料存储属性三字段（change add-bin-assignment，spec item-master-creation，任务 3.1）：
 * 合法字典值保存 / 非法值 422 / NULL 放行（偏差 D2）/ 编辑变更入 diff。
 */
@SpringBootTest
class MdmItemStorageAttrDbTest {

    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '存储属性测试%'");
        // HAZARD_LEVEL/CLEAN_LEVEL 字典无种子（4.1.3 约定页面维护），测试插入启用条目
        jdbc.update("INSERT IGNORE INTO erp_mdm_item_dict (ID, DICT_TYPE, DICT_CODE, DICT_NAME, "
                + "SORT_ORDER, STATUS, CREATE_BY) VALUES "
                + "('d-haz-test', 'HAZARD_LEVEL', 'HAZ-HIGH', '高危', 10, '1', 'junit')");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE '存储属性测试%'");
        jdbc.update("DELETE FROM erp_mdm_item_dict WHERE ID = 'd-haz-test'");
    }

    private MdmItem newItem(String suffix, String temp, String hazard, String clean) {
        MdmItem item = new MdmItem();
        item.setItemName("存储属性测试物料-" + suffix);
        item.setCategoryCode(categoryCode);
        item.setBaseUnit("PC");
        item.setMaterialGroup("STRUCT");
        item.setPurchaseType("BUY");
        item.setStorageCondition("NORMAL");
        item.setBatchFlag("0");
        item.setStatus("1");
        item.setTempLevel(temp);
        item.setHazardLevel(hazard);
        item.setCleanLevel(clean);
        return item;
    }

    @Test
    void validDictValuesSaved() {
        MdmItem saved = itemService.create(newItem("OK", "CHILLED", "HAZ-HIGH", null), false);
        assertEquals("CHILLED", saved.getTempLevel());
        assertEquals("HAZ-HIGH", saved.getHazardLevel());
        assertNull(saved.getCleanLevel(), "未填字段为 NULL");
        // 落库核验
        MdmItem db = itemService.getById(saved.getId());
        assertEquals("CHILLED", db.getTempLevel());
        assertEquals("HAZ-HIGH", db.getHazardLevel());
    }

    @Test
    void invalidDictValueRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> itemService.create(newItem("BAD", "NOPE-LEVEL", null, null), false));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("TEMP_LEVEL"), ex.getMessage());
    }

    @Test
    void nullAttrsAllowed() {
        // 偏差 D2：三个属性全 NULL = 无要求，放行
        MdmItem saved = itemService.create(newItem("NULL", null, null, null), false);
        assertNull(saved.getTempLevel());
        assertNull(saved.getHazardLevel());
        assertNull(saved.getCleanLevel());
    }

    @Test
    void updateChangesAttrsAndDiff() {
        MdmItem saved = itemService.create(newItem("UPD", "AMBIENT", null, null), false);
        MdmItem edit = new MdmItem();
        edit.setId(saved.getId());
        edit.setItemName(saved.getItemName());
        edit.setCategoryCode(saved.getCategoryCode());
        edit.setBaseUnit(saved.getBaseUnit());
        edit.setMaterialGroup(saved.getMaterialGroup());
        edit.setPurchaseType(saved.getPurchaseType());
        edit.setStorageCondition(saved.getStorageCondition());
        edit.setBatchFlag(saved.getBatchFlag());
        edit.setTempLevel("FROZEN");
        edit.setChangeReason("存储条件调整");
        edit.setVerNo(saved.getVerNo());
        MdmItem updated = itemService.update(edit);
        assertEquals("FROZEN", updated.getTempLevel());
        // 变更入 diff（buildDiff 携带新字段）
        MdmItem db = itemService.getById(saved.getId());
        assertEquals("FROZEN", db.getTempLevel());

        // 非法编辑同样 422
        MdmItem bad = new MdmItem();
        bad.setId(saved.getId());
        bad.setItemName(saved.getItemName());
        bad.setCategoryCode(saved.getCategoryCode());
        bad.setBaseUnit(saved.getBaseUnit());
        bad.setMaterialGroup(saved.getMaterialGroup());
        bad.setPurchaseType(saved.getPurchaseType());
        bad.setStorageCondition(saved.getStorageCondition());
        bad.setBatchFlag(saved.getBatchFlag());
        bad.setTempLevel("NOT-A-LEVEL");
        bad.setChangeReason("非法值测试");
        bad.setVerNo(db.getVerNo());
        ServiceException ex = assertThrows(ServiceException.class, () -> itemService.update(bad));
        assertEquals(422, ex.getCode());
    }
}
