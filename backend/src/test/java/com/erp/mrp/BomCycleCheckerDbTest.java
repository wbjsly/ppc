package com.erp.mrp;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 循环校验工具页扫描（change add-bom-management，任务 3.3）：
 * 全量扫描发现环（JDBC 注入绕过保存阻断构造环）/ 无环通过 / 按父项扫描范围正确。
 */
@SpringBootTest
class BomCycleCheckerDbTest {

    @Autowired
    private BomService bomService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem scanX;
    private MdmItem cycleA;
    private MdmItem cycleB;
    private MdmItem cycleC;

    @BeforeEach
    void seed() {
        cleanup();
        BomTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        categoryCode = jdbc.queryForObject(
                "SELECT CATEGORY_CODE FROM erp_mdm_item_category ORDER BY CATEGORY_CODE LIMIT 1",
                String.class);
        scanX = mkItem("BOM扫描测试无关父项朱雀甲");
        cycleA = mkItem("BOM扫描测试环节点玄武乙");
        cycleB = mkItem("BOM扫描测试环节点应龙丙");
        cycleC = mkItem("BOM扫描测试环节点鲲鹏丁");
    }

    @AfterEach
    void tearDown() {
        BomTestAuth.logout();
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE IN (" +
                "SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM扫描测试%')");
        // 崩溃残留：父项已不存在的版本行
        jdbc.update("DELETE FROM erp_mrp_bom WHERE PARENT_ITEM_CODE NOT IN " +
                "(SELECT ITEM_CODE FROM erp_mdm_item)");
        // 孤儿行清扫：行/替代只随版本行存在（测试用确定性 ID 时防跨用例主键冲突）
        jdbc.update("DELETE FROM erp_mrp_bom_item WHERE BOM_ID NOT IN (SELECT ID FROM erp_mrp_bom)");
        jdbc.update("DELETE FROM erp_mrp_bom_substitute WHERE BOM_ITEM_ID NOT IN " +
                "(SELECT ID FROM erp_mrp_bom_item)");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_NAME LIKE 'BOM扫描测试%'");
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

    /** JDBC 直接注入已发布版本（保存内嵌校验会阻断成环写入，扫描构造数据需绕过） */
    private void injectBom(MdmItem parent, MdmItem child) {
        String bomId = "scan-bom-" + parent.getItemCode();
        jdbc.update("INSERT INTO erp_mrp_bom (ID, PARENT_ITEM_CODE, PARENT_ITEM_NAME, " +
                        "VERSION_MAJOR, VERSION_MINOR, STATUS) VALUES (?,?,?,?,?,?)",
                bomId, parent.getItemCode(), parent.getItemName(), 1, 0, MrpBom.ST_PUBLISHED);
        jdbc.update("INSERT INTO erp_mrp_bom_item (ID, BOM_ID, LINE_NO, ITEM_CODE, ITEM_NAME, QTY, LOSS_RATE) " +
                        "VALUES (?,?,?,?,?,?,?)",
                "scan-item-" + parent.getItemCode(), bomId, 1,
                child.getItemCode(), child.getItemName(), BigDecimal.ONE, BigDecimal.ZERO);
    }

    @SuppressWarnings("unchecked")
    private List<String> cyclesOf(Map<String, Object> report) {
        return (List<String>) report.get("cycles");
    }

    @Test
    void fullScanFindsInjectedCycle() {
        injectBom(cycleA, cycleB);
        injectBom(cycleB, cycleC);
        injectBom(cycleC, cycleA);

        Map<String, Object> report = bomService.scan(null);
        assertEquals("ALL", report.get("scope"), "空入参 = 全量扫描");
        assertFalse((Boolean) report.get("passed"), "存在环时 passed=false");
        List<String> cycles = cyclesOf(report);
        assertEquals(1, cycles.size(), "全量扫描检出环");
        String path = cycles.get(0);
        assertTrue(path.contains(cycleA.getItemCode()) && path.contains(cycleB.getItemCode())
                        && path.contains(cycleC.getItemCode()),
                "环路径含三节点：" + path);
        assertTrue(path.contains("→"), "环路径以箭头串联");
        assertTrue((Long) report.get("scannedBoms") >= 3, "扫描 BOM 数统计");
    }

    @Test
    void scanPassesWithoutCycle() {
        // 正常建一条无环 BOM（服务层保存已过内嵌校验）
        MrpBom bom = new MrpBom();
        bom.setParentItemCode(scanX.getItemCode());
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(cycleA.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        bomService.create(bom, List.of(row));

        Map<String, Object> report = bomService.scan(null);
        assertTrue((Boolean) report.get("passed"), "无环时 passed=true");
        assertTrue(cyclesOf(report).isEmpty(), "无环列表为空");
        assertFalse((Boolean) report.get("depthExceeded"), "两层结构不超深度");
        assertTrue((Long) report.get("scannedBoms") >= 1, "统计到本条 BOM");
    }

    @Test
    void scanByParentScopedToSubgraph() {
        injectBom(cycleA, cycleB);
        injectBom(cycleB, cycleC);
        injectBom(cycleC, cycleA);

        // 扫描无关父项：不在环的可达子图内 → 无环；范围字段正确
        Map<String, Object> unrelated = bomService.scan(scanX.getItemCode());
        assertEquals(scanX.getItemCode(), unrelated.get("scope"), "scope=指定父项");
        assertEquals(1, unrelated.get("scannedRoots"), "单父项扫描");
        assertTrue(cyclesOf(unrelated).isEmpty(), "无关父项不背锅");

        // 从环上任一节点扫描：可达子图内检出环
        Map<String, Object> inCycle = bomService.scan(cycleA.getItemCode());
        assertFalse((Boolean) inCycle.get("passed"), "环内父项扫描应检出");
        assertFalse(cyclesOf(inCycle).isEmpty());
    }
}
