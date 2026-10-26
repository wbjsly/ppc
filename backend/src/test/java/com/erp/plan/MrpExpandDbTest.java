package com.erp.plan;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpBomSubstitute;
import com.erp.entity.mrp.MrpRun;
import com.erp.entity.mrp.MrpSuggestion;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MrpPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOM 展开与替代分配（change add-mrp-demand-planning，任务 3.1/3.2）：
 * 两层展开含损耗 / 深度上限截断 / 无 BOM 自制件直出生产建议 /
 * 替代优先级分配与比例换算溯源 / 多父件毛需求聚合。
 */
@SpringBootTest
class MrpExpandDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.erp.service.SysParamService sysParamService;

    private String categoryCode;

    @BeforeEach
    void seed() {
        PlanSeed.cleanup(jdbc);
        PlanTestAuth.login("planner-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG");
        categoryCode = PlanSeed.mkCategory(jdbc);
    }

    @AfterEach
    void tearDown() {
        // 深度参数复位（用例内可能调低；须走服务端 API 使进程内缓存失效）
        sysParamService.setValue("BOM_MAX_NESTING_DEPTH", "8", "COUNT", "MRP", "测试复位", "tester");
        PlanTestAuth.logout();
        PlanSeed.cleanup(jdbc);
    }

    private MdmItem mk(String name, String type) {
        return PlanSeed.mkItem(itemService, categoryCode, name, type);
    }

    private void publishBom(MdmItem parent, List<MrpBomItem> rows) {
        MrpBom head = new MrpBom();
        head.setParentItemCode(parent.getItemCode());
        MrpBom draft = bomService.create(head, rows);
        jdbc.update("UPDATE erp_mrp_bom SET STATUS='PENDING' WHERE ID=?", draft.getId());
        bomService.publishApproved(draft.getId());
    }

    private MrpBomItem line(MdmItem child, String qty, String loss) {
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(new BigDecimal(qty));
        row.setLossRate(new BigDecimal(loss));
        return row;
    }

    private Map<String, Object> suggestionOf(String itemCode) {
        List<Map<String, Object>> rows = planService.suggestions(null, null, null, itemCode, false);
        return rows.stream()
                .filter(r -> itemCode.equals(r.get("itemCode")))
                .findFirst()
                .orElse(null);
    }

    private BigDecimal val(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    // ---------- 3.1 展开三场景 ----------

    @Test
    void multiLevelExpandWithLoss() {
        MdmItem f = mk("展开成品F", "MAKE");
        MdmItem g = mk("展开半成品G", "MAKE");
        MdmItem h = mk("展开原料H", "BUY");
        // F --(×2, 损耗10%)--> G --(×1, 0%)--> H
        publishBom(f, List.of(line(g, "2", "10")));
        publishBom(g, List.of(line(h, "1", "0")));
        PlanSeed.mkSo(jdbc, f.getItemCode(), "10", "0", LocalDate.now().plusDays(12));

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        Map<String, Object> fr = suggestionOf(f.getItemCode());
        assertEquals(MrpSuggestion.TYPE_PRODUCTION, fr.get("type"), "成品出生产建议");
        assertEquals(0, val(fr, "suggestQty").compareTo(new BigDecimal("10")), "成品净 10");

        Map<String, Object> gr = suggestionOf(g.getItemCode());
        assertEquals(MrpSuggestion.TYPE_PRODUCTION, gr.get("type"), "半成品出生产建议");
        // 10 × 2 × (1+10%) = 22（FR-4.5-2-4 每层用量×(1+损耗率)）
        assertEquals(0, val(gr, "demandQty").compareTo(new BigDecimal("22")), "G 毛需求含损耗 22");
        assertEquals(0, val(gr, "suggestQty").compareTo(new BigDecimal("22")), "G 净 22");

        Map<String, Object> hr = suggestionOf(h.getItemCode());
        assertEquals(MrpSuggestion.TYPE_PURCHASE, hr.get("type"), "原料出采购建议");
        assertEquals(0, val(hr, "suggestQty").compareTo(new BigDecimal("22")), "H 承接 22");
    }

    @Test
    void depthCapTruncatesExplosion() {
        MdmItem f = mk("限深成品F", "MAKE");
        MdmItem g = mk("限深半成品G", "MAKE");
        MdmItem h = mk("限深原料H", "BUY");
        publishBom(f, List.of(line(g, "1", "0")));
        publishBom(g, List.of(line(h, "1", "0")));
        PlanSeed.mkSo(jdbc, f.getItemCode(), "5", "0", LocalDate.now().plusDays(12));
        // 深度上限 = 1：F(d0) 可爆炸，G(d1) 截断（服务端 API → 缓存失效）
        sysParamService.setValue("BOM_MAX_NESTING_DEPTH", "1", "COUNT", "MRP", "测试调低", "tester");

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        assertNotNull(suggestionOf(f.getItemCode()), "F 有建议");
        assertNotNull(suggestionOf(g.getItemCode()), "G 承接需求有建议");
        assertNull(suggestionOf(h.getItemCode()), "H 被深度截断（G 在 d=1 不再爆炸）");
    }

    @Test
    void makeWithoutBomDirectProductionSuggestion() {
        MdmItem solo = mk("无BOM自制件X", "MAKE");
        PlanSeed.mkSo(jdbc, solo.getItemCode(), "7", "0", LocalDate.now().plusDays(6));

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        Map<String, Object> row = suggestionOf(solo.getItemCode());
        assertEquals(MrpSuggestion.TYPE_PRODUCTION, row.get("type"), "无 BOM 自制件直出生产建议");
        assertEquals(0, val(row, "suggestQty").compareTo(new BigDecimal("7")), "净 7");
    }

    // ---------- 3.2 替代分配三场景 ----------

    @Test
    void substituteAllocationRatioAndTrace() {
        MdmItem p = mk("替代父件P", "MAKE");
        MdmItem m = mk("替代主料M", "BUY");
        MdmItem s1 = mk("替代料S1", "BUY");
        // P --(×1, 0%)--> M，替代 S1 优先级1 比例1.2；三者均无库存 → 缺量 12 → S1 承接 ×1.2
        MrpBomItem row = line(m, "1", "0");
        MrpBomSubstitute sub = new MrpBomSubstitute();
        sub.setSubstituteItemCode(s1.getItemCode());
        sub.setRatio(new BigDecimal("1.2"));
        sub.setPriority(1);
        row.setSubstitutes(List.of(sub));
        publishBom(p, List.of(row));
        PlanSeed.mkSo(jdbc, p.getItemCode(), "12", "0", LocalDate.now().plusDays(10));

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        Map<String, Object> sr = suggestionOf(s1.getItemCode());
        assertEquals(MrpSuggestion.TYPE_PURCHASE, sr.get("type"), "替代料出采购建议");
        // 12 × 1.2 = 14.4（spec FR-4.5-2-4 唯一具体场景）
        assertEquals(0, val(sr, "suggestQty").compareTo(new BigDecimal("14.4")),
                "比例换算 12×1.2=14.4：" + val(sr, "suggestQty"));
        assertTrue(String.valueOf(sr.get("remark")).contains("替代自 " + m.getItemCode()),
                "溯源备注：" + sr.get("remark"));
        assertTrue(String.valueOf(sr.get("remark")).contains("优先级 1"), "优先级留痕");
        // 主料池为 0 且缺量全给替代 → 主料无独立建议行
        assertNull(suggestionOf(m.getItemCode()), "主料无建议（缺量已分配替代）");
    }

    @Test
    void multiParentAggregatesChildDemand() {
        MdmItem p1 = mk("聚合父件P1", "MAKE");
        MdmItem p2 = mk("聚合父件P2", "MAKE");
        MdmItem c = mk("聚合子件C", "BUY");
        publishBom(p1, List.of(line(c, "1", "0")));
        publishBom(p2, List.of(line(c, "1", "0")));
        PlanSeed.mkSo(jdbc, p1.getItemCode(), "5", "0", LocalDate.now().plusDays(9));
        PlanSeed.mkSo(jdbc, p2.getItemCode(), "7", "0", LocalDate.now().plusDays(9));

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        List<Map<String, Object>> cRows = planService.suggestions(null, null, null, c.getItemCode(), false);
        long count = cRows.stream().filter(r -> c.getItemCode().equals(r.get("itemCode"))).count();
        assertEquals(1, count, "同物料仅一条建议（多父件聚合，不重复出建议）");
        Map<String, Object> cr = cRows.get(0);
        assertEquals(0, val(cr, "demandQty").compareTo(new BigDecimal("12")), "毛需求聚合 5+7=12");
        assertEquals(0, val(cr, "suggestQty").compareTo(new BigDecimal("12")), "净 12");
    }

    private static void assertNotNull(Object o, String msg) {
        org.junit.jupiter.api.Assertions.assertNotNull(o, msg);
    }
}
