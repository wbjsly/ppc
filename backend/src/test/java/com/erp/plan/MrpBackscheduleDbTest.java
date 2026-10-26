package com.erp.plan;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpRun;
import com.erp.entity.mrp.MrpSuggestion;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MrpPlanService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐层倒排与逾期标记（change add-mrp-demand-planning，任务 4.1）：
 * 子件需求日=父件开工日（路线 ΣLT）/ 缺路线兜底 leadTimeDays 行内提示 / 下单日早于今日 OVERDUE。
 */
@SpringBootTest
class MrpBackscheduleDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        PlanSeed.cleanup(jdbc);
        PlanTestAuth.login("planner-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG");
        categoryCode = PlanSeed.mkCategory(jdbc);
    }

    @AfterEach
    void tearDown() {
        PlanTestAuth.logout();
        PlanSeed.cleanup(jdbc);
    }

    private MdmItem mk(String name, String type, java.util.function.Consumer<MdmItem> extra) {
        return PlanSeed.mkItem(itemService, categoryCode, name, type, extra);
    }

    private void publishBom(MdmItem parent, MdmItem child) {
        MrpBom head = new MrpBom();
        head.setParentItemCode(parent.getItemCode());
        MrpBomItem row = new MrpBomItem();
        row.setItemCode(child.getItemCode());
        row.setQty(BigDecimal.ONE);
        row.setLossRate(BigDecimal.ZERO);
        MrpBom draft = bomService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_bom SET STATUS='PENDING' WHERE ID=?", draft.getId());
        bomService.publishApproved(draft.getId());
    }

    private Map<String, Object> row(String itemCode) {
        List<Map<String, Object>> rows = planService.suggestions(null, null, null, itemCode, false);
        return rows.stream().filter(r -> itemCode.equals(r.get("itemCode"))).findFirst().orElse(null);
    }

    @Test
    void layerBackscheduleUsesParentStartAsChildReqDate() {
        MdmItem f = mk("倒排成品F", "MAKE", i -> i.setLeadTimeDays(3));
        MdmItem c = mk("倒排采购件C", "BUY", i -> i.setLeadTimeDays(10));
        publishBom(f, c);
        // 已发布路线：F 装配 7 天（覆盖 leadTimeDays=3）
        PlanSeed.publishRouting(routingService, operationService, workCenterService,
                standardService, jdbc, f, 7);
        LocalDate due = LocalDate.now().plusDays(20);
        PlanSeed.mkSo(jdbc, f.getItemCode(), "10", "0", due);

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        Map<String, Object> fr = row(f.getItemCode());
        LocalDate fStart = LocalDate.parse(String.valueOf(fr.get("orderDate")));
        assertEquals(due.minusDays(7), fStart, "父件开工日 = 需求日 − 路线 ΣLT(7)");

        Map<String, Object> cr = row(c.getItemCode());
        assertEquals(due.minusDays(7), LocalDate.parse(String.valueOf(cr.get("reqDate"))),
                "子件需求日 = 父件开工日");
        assertEquals(due.minusDays(7 + 10), LocalDate.parse(String.valueOf(cr.get("orderDate"))),
                "采购件下单日 = 需求日 − leadTimeDays(10)");
        assertEquals("0", cr.get("overdueFlag"), "未来日期不逾期");
    }

    @Test
    void missingRouteFallsBackToMaterialLeadTimeWithHint() {
        MdmItem g = mk("无路线自制件G", "MAKE", i -> i.setLeadTimeDays(3));
        MdmItem c = mk("无路线采购件C", "BUY", i -> i.setLeadTimeDays(1));
        publishBom(g, c);
        LocalDate due = LocalDate.now().plusDays(10);
        PlanSeed.mkSo(jdbc, g.getItemCode(), "4", "0", due);

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        Map<String, Object> gr = row(g.getItemCode());
        assertNotNull(gr, "自制件有建议");
        assertTrue(String.valueOf(gr.get("remark")).contains("兜底"),
                "缺路线兜底提示在行内：" + gr.get("remark"));
        assertEquals(due.minusDays(3), LocalDate.parse(String.valueOf(gr.get("orderDate"))),
                "兜底用 leadTimeDays=3");

        Map<String, Object> cr = row(c.getItemCode());
        assertEquals(due.minusDays(3 + 1), LocalDate.parse(String.valueOf(cr.get("orderDate"))),
                "子件逐层倒排接力");
    }

    @Test
    void orderDateBeforeTodayMarksOverdue() {
        MdmItem d = mk("逾期采购件D", "BUY", i -> i.setLeadTimeDays(30));
        PlanSeed.mkSo(jdbc, d.getItemCode(), "5", "0", LocalDate.now().plusDays(5));

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        Map<String, Object> dr = row(d.getItemCode());
        assertEquals("1", dr.get("overdueFlag"), "下单日(−25d) < 今日 → OVERDUE");
        assertTrue(LocalDate.parse(String.valueOf(dr.get("orderDate"))).isBefore(LocalDate.now()),
                "下单日早于今日");
        assertEquals(MrpSuggestion.ST_PENDING, dr.get("status"), "逾期仍可确认（不阻断）");

        // OVERDUE 出现在异常聚合视图（5.3.3）
        List<Map<String, Object>> ex = planService.suggestions(null, null, null, null, true);
        assertTrue(ex.stream().anyMatch(r -> d.getItemCode().equals(r.get("itemCode"))),
                "逾期行进异常标记页");
    }
}
