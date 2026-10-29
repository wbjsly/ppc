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

    /** 日历快照（CAL_DATE → [IS_WORKDAY, REMARK] 或 null=原本无行）：tearDown 恢复种子原状 */
    private final java.util.Map<LocalDate, String[]> calBackup = new java.util.HashMap<>();

    @AfterEach
    void tearDown() {
        PlanTestAuth.logout();
        PlanSeed.cleanup(jdbc);
        // 恢复日历快照（测试只改 2026-2027 种子行的 IS_WORKDAY/REMARK——不删种子里的行）
        calBackup.forEach((d, orig) -> {
            if (orig == null) {
                jdbc.update("DELETE FROM erp_admin_work_calendar WHERE CAL_DATE=? AND CREATE_BY='mrpbt'", d);
            } else {
                jdbc.update("UPDATE erp_admin_work_calendar SET IS_WORKDAY=?, REMARK=? WHERE CAL_DATE=?",
                        orig[0], orig[1], d);
            }
        });
        calBackup.clear();
    }

    /** upsert 并记快照（种子行被改动也能在 tearDown 还原） */
    private void upsertCal(LocalDate d, String isWorkday, String remark) {
        calBackup.computeIfAbsent(d, k -> jdbc.query(
                "SELECT IS_WORKDAY, REMARK FROM erp_admin_work_calendar WHERE CAL_DATE=?",
                rs -> rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null, k));
        jdbc.update("INSERT INTO erp_admin_work_calendar (ID,CAL_DATE,IS_WORKDAY,REMARK,"
                        + "CREATE_BY,DEL_FLAG,VER_NO) VALUES (?,?,?,?,'mrpbt','0',0) "
                        + "ON DUPLICATE KEY UPDATE IS_WORKDAY=?, REMARK=?",
                "mrpbt-" + d, d, isWorkday, remark, isWorkday, remark);
    }

    /**
     * 造全工作日日历（含周末也置 1）：让工作日回推结果 == 日历日减法，
     * 既有精确断言（due.minusDays(N)）在任何运行星期下都成立。
     */
    private void seedAllWorkdays(LocalDate from, LocalDate to) {
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            upsertCal(d, "1", "回推测试");
        }
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
        seedAllWorkdays(due.minusDays(30), due); // 全工作日 → 回推 == 日历日减法（断言精确）

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
        seedAllWorkdays(due.minusDays(20), due); // 全工作日 → 兜底断言精确

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

    @Test
    void backoffSkipsWeekendHolidayAndFallbackDays() {
        // spec mrp-demand-planning「逐层提前期倒排」MODIFIED：需求日 回推 N 个工作日。
        // 造数：due=周三；日历 [due−9, due] 内工作日（周一~五）置 1、due−1（周二）置假日非工作日；
        // 周末无日历行 → KITTING_CALENDAR_FALLBACK=WEEKDAY 降级跳过（降级口径与产能侧一致）。
        MdmItem d = mk("工作日回推采购件W", "BUY", i -> i.setLeadTimeDays(5));
        LocalDate due = LocalDate.now().plusDays(21);
        while (due.getDayOfWeek() != java.time.DayOfWeek.WEDNESDAY) {
            due = due.plusDays(1);
        }
        LocalDate holiday = due.minusDays(1); // 周二假日
        for (LocalDate x = due.minusDays(9); !x.isAfter(due); x = x.plusDays(1)) {
            if (x.equals(holiday)) {
                upsertCal(x, "0", "测试假日"); // 假日：以行为准非工作日
            } else if (x.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && x.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                upsertCal(x, "1", "回推测试"); // 工作日
            }
            // 周末不动：种子行 IS_WORKDAY=0（以行为准跳过）；种子缺失时 Fallback=WEEKDAY 跳过
        }
        PlanSeed.mkSo(jdbc, d.getItemCode(), "5", "0", due);

        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);

        Map<String, Object> dr = row(d.getItemCode());
        // 回推 5 个工作日：周二假日跳过 + 周末无行降级跳过 → 命中 due−8
        // （数：−2 周一? 不——路径 due−1 假日、−2 周一✓、−3/−4 周末、−5 周五✓、−6 周四✓、−7 周三✓、−8 周二✓）
        assertEquals(due.minusDays(8), LocalDate.parse(String.valueOf(dr.get("orderDate"))),
                "回推 5 个工作日：跳过周二假日与周末（无行 Fallback 跳过），落 due−8");
    }
}
