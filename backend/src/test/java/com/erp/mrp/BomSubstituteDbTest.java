package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpBomSubstitute;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOM 行级替代（change add-bom-management，spec bom-management FR-4.5-1-3，任务 2.4）：
 * 多替代 1:N 保存（比例+优先级）/ 主料与替代料单位不一致 422（偏差 D2）/
 * MDM 物料替代关系候选带出（只读引用，不强耦合）。
 */
@SpringBootTest
class BomSubstituteDbTest {

    @Autowired
    private BomService bomService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem parent;
    private MdmItem main;
    private MdmItem altA;
    private MdmItem altB;

    @BeforeEach
    void seed() {
        cleanup();
        BomTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
        parent = mkItem("BOM替代测试父项玄武甲", "PC");
        main = mkItem("BOM替代测试主料朱雀乙", "PC");
        altA = mkItem("BOM替代测试替代料白虎丙", "PC");
        altB = mkItem("BOM替代测试替代料玄鸟丁", "PC");
    }

    @AfterEach
    void tearDown() {
        BomTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM替代测试%' " +
                "OR ITEM_NAME LIKE 'BOM测试%' OR ITEM_NAME LIKE 'BOM生命周期测试%' " +
                "OR ITEM_NAME LIKE 'BOM扫描测试%')");
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE NOT IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item)");
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom)");
        jdbc.update("DELETE FROM erp_mrp_bom_substitute WHERE BOM_ITEM_ID NOT IN " +
                "(SELECT ID FROM erp_mrp_bom_item)");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM替代测试%' OR ITEM_NAME LIKE 'BOM测试%'");
    }

    private MdmItem mkItem(String name, String unit) {
        MdmItem item = new MdmItem();
        item.setItemName(name);
        item.setCategoryCode(categoryCode);
        item.setBaseUnit(unit);
        item.setMaterialGroup("STRUCT");
        item.setPurchaseType("BUY");
        item.setStorageCondition("NORMAL");
        item.setBatchFlag("0");
        item.setPackingSpec("盒");
        item.setDupNote("BOM 模块测试物料，非业务重复数据");
        return itemService.create(item, true);
    }

    private MrpBomSubstitute sub(MdmItem target, String ratio, Integer priority) {
        MrpBomSubstitute s = new MrpBomSubstitute();
        s.setSubstituteItemCode(target.getItemCode());
        s.setRatio(new BigDecimal(ratio));
        s.setPriority(priority);
        return s;
    }

    private MrpBomItem lineWith(MdmItem item, List<MrpBomSubstitute> subs) {
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(item.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        row.setSubstitutes(subs);
        return row;
    }

    private MrpBom createWithLine(MrpBomItem line) {
        MrpBom head = new MrpBom();
        head.setParentItemCode(parent.getItemCode());
        return bomService.create(head, List.of(line));
    }

    // ---------- 2.4 三场景 ----------

    @Test
    void multiSubstitutesSavedWithRatioAndPriority() {
        MrpBom bom = createWithLine(lineWith(main,
                List.of(sub(altA, "1.2", 1), sub(altB, "1.5", 2))));

        Map<String, Object> detail = bomService.detail(bom.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        assertEquals(1, items.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subs = (List<Map<String, Object>>) items.get(0).get("substitutes");
        assertEquals(2, subs.size(), "1:N 两个替代料");
        // 按优先级排序：priority=1 在前
        assertEquals(altA.getItemCode(), subs.get(0).get("substituteItemCode"), "优先级 1 在前");
        assertEquals(0, new BigDecimal("1.2")
                .compareTo(new BigDecimal(subs.get(0).get("ratio").toString())), "比例 1.2 落库");
        assertEquals(2, subs.get(1).get("priority"), "优先级 2 落库");
    }

    @Test
    void unitMismatchRejected() {
        // 主料单位 PC，替代料单位取字典中另一个单位（偏差 D2：单位不一致 L1 阻断）
        String otherUnit = itemService.dicts("UNIT").stream()
                .map(m -> m.get("code"))
                .filter(c -> !"PC".equals(c))
                .findFirst()
                .orElseThrow(() -> new AssertionError("UNIT 字典须存在 PC 以外的单位"));
        MdmItem kgItem = mkItem("BOM替代测试公斤料戊己庚", otherUnit);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> createWithLine(lineWith(main, List.of(sub(kgItem, "1", 1)))));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("单位"), "提示单位一致：" + ex.getMessage());
    }

    @Test
    void candidatesFromMdmRelation() {
        // 无 MDM 替代关系 → 空候选
        assertTrue(bomService.substituteCandidates(main.getItemCode()).isEmpty(), "无关系返回空");

        // 建立 MDM 物料级替代（菜单 1.2.5）→ 候选带出
        itemService.setSubstitute(main.getId(), altA.getItemCode());
        List<Map<String, Object>> cands = bomService.substituteCandidates(main.getItemCode());
        assertEquals(1, cands.size(), "MDM 关系带出一个候选");
        assertEquals(altA.getItemCode(), cands.get(0).get("itemCode"), "候选即 MDM 替代目标");
        assertEquals("PC", cands.get(0).get("baseUnit"), "候选带出单位供前端预填");
        assertEquals("MDM", cands.get(0).get("source"), "来源标记为 MDM");
    }
}
