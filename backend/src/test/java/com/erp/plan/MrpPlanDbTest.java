package com.erp.plan;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpSuggestion;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.MrpPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺料→MRP 联动（change add-kitting-check 任务 5.3/5.4，spec mrp-demand-planning MODIFIED）：
 * 5.3 缺料转采购建议（[SHORTAGE:MO_NO] 来源标识、防重 422、单一漏斗）；
 * 5.4 mergeShortageDemand SO 覆盖判定（足额覆盖→跳过防双计 / 手工工单→并入 / 范围外→不并入）。
 */
@SpringBootTest
class MrpPlanDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem child;

    @BeforeEach
    void seed() {
        PlanSeed.cleanup(jdbc);
        PlanTestAuth.login("planner-tester", "ROLE_PLANNER");
        categoryCode = PlanSeed.mkCategory(jdbc);
    }

    @AfterEach
    void tearDown() {
        PlanTestAuth.logout();
        PlanSeed.cleanup(jdbc);
    }

    // ---------- 任务 5.3：缺料转采购建议 ----------

    @Test
    void convertFromShortageCreatesPendingPurchaseSuggestion() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "缺料转建议产品", "MAKE");
        child = PlanSeed.mkItem(itemService, categoryCode, "缺料转建议子件", "BUY");
        String moId = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        String shortageId = PlanSeed.mkShortage(jdbc, moId, child.getItemCode(),
                "20", "0", "20");

        Map<String, Object> out = planService.convertFromShortage(List.of(shortageId));
        assertEquals(1, out.get("created"), "转出一条建议");

        @SuppressWarnings("unchecked")
        var sugs = (List<Map<String, Object>>) out.get("suggestions");
        assertEquals(1, sugs.size(), "建议列表一条");
        Map<String, Object> s = sugs.get(0);
        assertEquals(child.getItemCode(), s.get("itemCode"), "物料编码");
        assertEquals(MrpSuggestion.TYPE_PURCHASE, s.get("type"), "TYPE=PURCHASE");
        assertEquals("PENDING", s.get("status"), "待审核");
        // 五值留痕与缺料行快照一致
        assertEquals(0, bd(s.get("netReq")).compareTo(new java.math.BigDecimal("20")), "NET_REQ = 缺料量");
        String remark = String.valueOf(s.get("remark"));
        assertTrue(remark.contains("[SHORTAGE:"), "REMARK 含来源标识");
        assertTrue(remark.contains("缺料转采购建议"), "REMARK 含来源说明");

        // 5.3.1 列表可见（PENDING 采购建议）
        List<Map<String, Object>> list = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, "PENDING", null, child.getItemCode(), false);
        assertTrue(list.stream().anyMatch(r -> String.valueOf(r.get("remark"))
                        .contains("[SHORTAGE:")),
                "5.3.1 列表可见缺料来源建议");

        // 防重：同工单同物料重复转 → 422（未处置前）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.convertFromShortage(List.of(shortageId)));
        assertEquals(422, ex.getCode(), "重复转 422");
        assertTrue(ex.getMessage().contains("已存在未处置"), "422 提示语");
    }

    /** 已处置（取消）后允许再转（防重只看 PENDING/CONFIRMED） */
    @Test
    void convertFromShortageAllowsRetryAfterCancel() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "缺料重试产品", "MAKE");
        child = PlanSeed.mkItem(itemService, categoryCode, "缺料重试子件", "BUY");
        String moId = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        String shortageId = PlanSeed.mkShortage(jdbc, moId, child.getItemCode(),
                "20", "0", "20");

        planService.convertFromShortage(List.of(shortageId));
        List<Map<String, Object>> list = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, "PENDING", null, child.getItemCode(), false);
        String sugId = (String) list.get(0).get("id");
        planService.cancel(sugId, "改由 MRP 建议覆盖"); // 终态 → 防重放行

        Map<String, Object> retry = planService.convertFromShortage(List.of(shortageId));
        assertEquals(1, retry.get("created"), "已处置后可再转");
    }

    /** 权限：非 PLANNER/ADMIN → 403 */
    @Test
    void convertFromShortagePermissionGate() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "缺料权限产品", "MAKE");
        child = PlanSeed.mkItem(itemService, categoryCode, "缺料权限子件", "BUY");
        String moId = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        String shortageId = PlanSeed.mkShortage(jdbc, moId, child.getItemCode(),
                "20", "0", "20");
        PlanTestAuth.login("warehouse", "ROLE_WAREHOUSE"); // 缺料查看可、转建议不可（MrpPlan 口径 PLANNER/ADMIN）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.convertFromShortage(List.of(shortageId)));
        assertEquals(403, ex.getCode(), "非计划员 403");
    }

    // ---------- 任务 5.4：mergeShortageDemand SO 覆盖判定 ----------

    /** SO 未交量足额覆盖工单剩余量 → 跳过（SO 展开已覆盖，防双计） */
    @Test
    void shortageSkippedWhenSoCoversRemaining() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "覆盖判定产品", "MAKE");
        child = PlanSeed.mkItem(itemService, categoryCode, "覆盖判定子件", "BUY");
        // SO 未交 100 ≥ 工单剩余 10 → 覆盖
        PlanSeed.mkSo(jdbc, product.getItemCode(), "100", "0", LocalDate.now().plusDays(9));
        String moId = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        PlanSeed.mkShortage(jdbc, moId, child.getItemCode(), "20", "0", "20");

        planService.run("FULL", null);

        // 子件无 SO 无 ROP，需求只可能来自缺料并入 → 覆盖判定生效则不应出建议
        List<Map<String, Object>> childSugs = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, null, null, child.getItemCode(), false);
        assertTrue(childSugs.stream().noneMatch(s ->
                        child.getItemCode().equals(s.get("itemCode"))),
                "SO 足额覆盖 → 缺料不并入（防双计）");
    }

    /** 无 SO 手工工单缺料 → 全额并入 gross 产出建议 */
    @Test
    void shortageMergedForManualMoWithoutSo() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "手工工单产品", "MAKE");
        child = PlanSeed.mkItem(itemService, categoryCode, "手工工单子件", "BUY");
        // 无 SO → 覆盖判定不过 → 并入
        String moId = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        PlanSeed.mkShortage(jdbc, moId, child.getItemCode(), "20", "0", "20");

        planService.run("FULL", null);

        List<Map<String, Object>> childSugs = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, null, null, child.getItemCode(), false);
        assertEquals(1, childSugs.size(), "无 SO 手工工单缺料 → 产出采购建议");
        // NET_REQ 含并入的缺料量 20（无库存无在途）
        assertEquals(0, bd(childSugs.get(0).get("netReq")).compareTo(new java.math.BigDecimal("20")),
                "净需求 = 缺料量 20");
    }

    /** 范围过滤：CATEGORY 运行只并入范围内工单的缺料（工单产品在 closure 内） */
    @Test
    void shortageRespectsRunScope() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "范围过滤产品", "MAKE");
        child = PlanSeed.mkItem(itemService, categoryCode, "范围过滤子件", "BUY");
        // 第二个分类的物料（范围外）
        jdbc.update("INSERT IGNORE INTO erp_mdm_item_category (ID, CATEGORY_CODE, CATEGORY_NAME, ITEM_PREFIX) " +
                "VALUES ('cat-rplan-out', 'RPOU', '计划测试范围外分类', 'RPOU')");
        MdmItem outItem = PlanSeed.mkItem(itemService, "RPOU", "范围外子件", "BUY");
        jdbc.update("DELETE FROM erp_mdm_item_category WHERE ID='cat-rplan-out'");

        String moIn = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        PlanSeed.mkShortage(jdbc, moIn, child.getItemCode(), "20", "0", "20");
        // 范围外工单的缺料（产品也在范围外分类）——产品须在 closure 判定，范围外产品工单不并入
        String moOut = PlanSeed.mkMo(jdbc, outItem.getItemCode(), "10", "RELEASED");
        PlanSeed.mkShortage(jdbc, moOut, outItem.getItemCode(), "30", "0", "30");

        planService.run("CATEGORY", categoryCode);

        // 范围内：缺料并入 → 有建议
        List<Map<String, Object>> inSugs = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, null, null, child.getItemCode(), false);
        assertEquals(1, inSugs.size(), "范围内工单缺料并入");
        // 范围外：不并入
        List<Map<String, Object>> outSugs = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, null, null, outItem.getItemCode(), false);
        assertTrue(outSugs.isEmpty(), "范围外工单缺料不并入");
    }

    /** 关闭工单的缺料不并入（未关闭口径） */
    @Test
    void closedMoShortageIgnored() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "关闭工单产品", "MAKE");
        child = PlanSeed.mkItem(itemService, categoryCode, "关闭工单子件", "BUY");
        String moId = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "CLOSED");
        PlanSeed.mkShortage(jdbc, moId, child.getItemCode(), "20", "0", "20");

        planService.run("FULL", null);

        List<Map<String, Object>> sugs = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, null, null, child.getItemCode(), false);
        assertTrue(sugs.isEmpty(), "CLOSED 工单缺料不并入");
    }

    /** 断言辅助：JSON 数值 → BigDecimal */
    private static java.math.BigDecimal bd(Object o) {
        return new java.math.BigDecimal(String.valueOf(o));
    }
}
