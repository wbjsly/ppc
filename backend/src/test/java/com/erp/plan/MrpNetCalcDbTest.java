package com.erp.plan;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpRun;
import com.erp.entity.mrp.MrpSuggestion;
import com.erp.service.mdm.MdmItemService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 净算与建议落库（change add-mrp-demand-planning，任务 2.2/2.3）：
 * 五值留痕（SO+库存+在途，制恒 0）/ 过量 EXCESS / ROP 补货触发 / MOQ 上调 / 超最大库存提示。
 */
@SpringBootTest
class MrpNetCalcDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

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

    private MrpSuggestion rowOf(String itemCode, String type) {
        List<Map<String, Object>> rows = planService.suggestions(type, null, null, itemCode, false);
        assertTrue(rows.stream().anyMatch(r -> itemCode.equals(r.get("itemCode"))),
                "建议列表应含 " + itemCode + "：" + rows);
        return jdbc.queryForObject("SELECT * FROM erp_mrp_suggestion WHERE ITEM_CODE=? AND TYPE=?",
                (rs, i) -> {
                    MrpSuggestion s = new MrpSuggestion();
                    s.setItemCode(rs.getString("ITEM_CODE"));
                    s.setType(rs.getString("TYPE"));
                    s.setDemandQty(rs.getBigDecimal("DEMAND_QTY"));
                    s.setOnHandQty(rs.getBigDecimal("ON_HAND_QTY"));
                    s.setInProcessQty(rs.getBigDecimal("IN_PROCESS_QTY"));
                    s.setInTransitQty(rs.getBigDecimal("IN_TRANSIT_QTY"));
                    s.setNetReq(rs.getBigDecimal("NET_REQ"));
                    s.setSuggestQty(rs.getBigDecimal("SUGGEST_QTY"));
                    s.setRemark(rs.getString("REMARK"));
                    s.setOverdueFlag(rs.getString("OVERDUE_FLAG"));
                    s.setStatus(rs.getString("STATUS"));
                    return s;
                }, itemCode, type);
    }

    @Test
    void netCalcFiveValuesWithExcessRopMoqAndMaxStock() {
        MdmItem hit = PlanSeed.mkItem(itemService, categoryCode, "净算命中甲", "BUY");
        MdmItem excess = PlanSeed.mkItem(itemService, categoryCode, "过量供给乙", "BUY");
        MdmItem rop = PlanSeed.mkItem(itemService, categoryCode, "ROP补货丙", "BUY",
                i -> i.setRopQty(new BigDecimal("50")));
        MdmItem moq = PlanSeed.mkItem(itemService, categoryCode, "MOQ调整丁", "BUY",
                i -> i.setMinOrderQty(new BigDecimal("50")));
        MdmItem maxs = PlanSeed.mkItem(itemService, categoryCode, "超库存戊", "BUY",
                i -> i.setMaxStock(new BigDecimal("200")));

        // 数据源：A 需求100/库存30/在途20；B 需求50/库存80；C 库存30+ROP50；D 需求30；E 需求20/库存190
        PlanSeed.mkSo(jdbc, hit.getItemCode(), "100", "0", LocalDate.now().plusDays(10));
        PlanSeed.mkStock(jdbc, hit.getItemCode(), "30");
        PlanSeed.mkPo(jdbc, hit.getItemCode(), "20", "0");

        PlanSeed.mkSo(jdbc, excess.getItemCode(), "50", "0", LocalDate.now().plusDays(5));
        PlanSeed.mkStock(jdbc, excess.getItemCode(), "80");

        PlanSeed.mkStock(jdbc, rop.getItemCode(), "30");

        PlanSeed.mkSo(jdbc, moq.getItemCode(), "30", "0", LocalDate.now().plusDays(7));
        // 可用+建议 = 需求量（净>0 时恒等）→ 超 200 上限须需求 210；净=200
        PlanSeed.mkSo(jdbc, maxs.getItemCode(), "210", "0", LocalDate.now().plusDays(7));
        PlanSeed.mkStock(jdbc, maxs.getItemCode(), "10");

        Map<String, Object> run = planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        assertEquals(MrpRun.ST_DONE, run.get("runStatus"), "运行完成");

        // 2.2 净算命中：五值留痕 + 在制恒 0
        MrpSuggestion a = rowOf(hit.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        assertEquals(0, a.getDemandQty().compareTo(new BigDecimal("100.0000")), "需求留痕 100");
        assertEquals(0, a.getOnHandQty().compareTo(new BigDecimal("30.0000")), "库存留痕 30");
        assertEquals(0, a.getInProcessQty().compareTo(BigDecimal.ZERO), "在制桩 0");
        assertEquals(0, a.getInTransitQty().compareTo(new BigDecimal("20.0000")), "在途留痕 20");
        assertEquals(0, a.getNetReq().compareTo(new BigDecimal("50.0000")), "NetReq=50");
        assertEquals(MrpSuggestion.ST_PENDING, a.getStatus(), "初始待审核");

        // 2.3 过量供给 → EXCESS 不可转正（类型即门）
        MrpSuggestion ex = rowOf(excess.getItemCode(), MrpSuggestion.TYPE_EXCESS);
        assertTrue(ex.getNetReq().signum() < 0, "EXCESS 净为负：" + ex.getNetReq());
        assertTrue(ex.getRemark() != null && ex.getRemark().contains("过量供给"), "过量提示留痕");

        // 2.3 ROP 补货：初始可用 30 < ROP 50 → 需求按目标水位 50 计入，净 = 20
        MrpSuggestion r = rowOf(rop.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        assertEquals(0, r.getDemandQty().compareTo(new BigDecimal("50.0000")), "ROP 目标水位计入需求");
        assertEquals(0, r.getNetReq().compareTo(new BigDecimal("20.0000")), "净=缺口 20");

        // 2.3 MOQ 上调：净 30 < 50 → 建议量 50 + 备注
        MrpSuggestion m = rowOf(moq.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        assertEquals(0, m.getSuggestQty().compareTo(new BigDecimal("50.0000")), "建议量上调 MOQ");
        assertTrue(m.getRemark() != null && m.getRemark().contains("最小订购量"), "MOQ 备注：" + m.getRemark());

        // 2.3 超最大库存：可用 190 + 建议 20 > 200 → 行内提示（不阻断）
        MrpSuggestion e = rowOf(maxs.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        assertEquals(0, e.getSuggestQty().compareTo(new BigDecimal("200.0000")), "建议量=净需求 200");
        assertNotNull(e.getRemark(), "超库存提示存在");
        assertTrue(e.getRemark().contains("超最大库存"), "超最大库存备注：" + e.getRemark());
        assertEquals(MrpSuggestion.ST_PENDING, e.getStatus(), "超库存不阻断审核");
    }

    @Test
    void emptyScopeRunFailsAndRollsBack() {
        PlanSeed.mkItem(itemService, categoryCode, "空范围己", "BUY");
        com.erp.common.ServiceException ex = assertThrows(com.erp.common.ServiceException.class,
                () -> planService.run(MrpRun.SCOPE_CATEGORY, "NO-SUCH-CATEGORY-" + System.nanoTime()));
        assertEquals(422, ex.getCode(), "空范围 422");
        // 失败回滚：FAILED 运行留痕、无建议落库（task 5.1）
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_run WHERE RUN_STATUS='FAILED'", Integer.class);
        assertEquals(1, failed, "FAILED 运行留痕");
        Integer sugs = jdbc.queryForObject("SELECT COUNT(*) FROM erp_mrp_suggestion", Integer.class);
        assertEquals(0, sugs, "失败无建议落库");
    }
}
