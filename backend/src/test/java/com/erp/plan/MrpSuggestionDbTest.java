package com.erp.plan;

import com.erp.common.ServiceException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核生命周期与异常处置（change add-mrp-demand-planning，任务 6.1/6.4）：
 * 确认改量留痕 / 取消必填 / 状态门与 CAS / EXCESS 拒绝确认 /
 * 异常处置留痕 / 备注必填 / 非异常行拒绝。
 */
@SpringBootTest
class MrpSuggestionDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem normal;
    private MdmItem excess;

    @BeforeEach
    void seed() {
        PlanSeed.cleanup(jdbc);
        PlanTestAuth.login("planner-tester", "ROLE_PLANNER");
        categoryCode = PlanSeed.mkCategory(jdbc);
        normal = PlanSeed.mkItem(itemService, categoryCode, "审核常规件", "BUY",
                i -> i.setLeadTimeDays(5));
        excess = PlanSeed.mkItem(itemService, categoryCode, "审核过量件", "BUY");
        PlanSeed.mkSo(jdbc, normal.getItemCode(), "40", "0", LocalDate.now().plusDays(15));
        PlanSeed.mkSo(jdbc, excess.getItemCode(), "10", "0", LocalDate.now().plusDays(15));
        PlanSeed.mkStock(jdbc, excess.getItemCode(), "90");
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
    }

    @AfterEach
    void tearDown() {
        PlanTestAuth.logout();
        PlanSeed.cleanup(jdbc);
    }

    private String idOf(String itemCode, String type) {
        List<Map<String, Object>> rows = planService.suggestions(type, null, null, itemCode, false);
        return rows.stream()
                .filter(r -> itemCode.equals(r.get("itemCode")))
                .map(r -> String.valueOf(r.get("id")))
                .findFirst().orElseThrow(() -> new AssertionError("无建议行 " + itemCode));
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_mrp_suggestion WHERE ID=?", String.class, id);
    }

    // ---------- 6.1 审核生命周期 ----------

    @Test
    void confirmAdjustsQtyWithOriginalKept() {
        String id = idOf(normal.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        Map<String, Object> out = planService.confirm(id, new BigDecimal("35"),
                LocalDate.now().plusDays(20));
        assertEquals(MrpSuggestion.ST_CONFIRMED, out.get("status"), "确认成功");
        // 原值留痕 + 确认值
        Object orig = out.get("origSuggestQty");
        assertEquals(0, new BigDecimal(orig.toString()).compareTo(new BigDecimal("40")), "原建议量留痕");
        assertEquals(0, new BigDecimal(out.get("confirmQty").toString()).compareTo(new BigDecimal("35")),
                "确认量 35");
        assertEquals(MrpSuggestion.ST_CONFIRMED, statusOf(id), "CAS 状态落库");
        assertEquals("planner-tester", out.get("confirmBy"), "确认人留痕");
    }

    @Test
    void cancelRequiresReasonAndGatesStatus() {
        String id = idOf(normal.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        // 缺原因 422
        ServiceException noReason = assertThrows(ServiceException.class,
                () -> planService.cancel(id, "  "));
        assertEquals(422, noReason.getCode(), "取消原因必填 422");

        planService.cancel(id, "需求取消，改为调拨");
        assertEquals(MrpSuggestion.ST_CANCELLED, statusOf(id), "已取消");
        // 已取消不可再确认（状态门）
        ServiceException after = assertThrows(ServiceException.class,
                () -> planService.confirm(id, null, null));
        assertEquals(422, after.getCode(), "已取消不可确认 422");
        // 已取消不可再取消
        ServiceException twice = assertThrows(ServiceException.class,
                () -> planService.cancel(id, "再次取消"));
        assertEquals(422, twice.getCode(), "终态不可再取消 422");
    }

    @Test
    void excessRowCannotBeConfirmed() {
        String id = idOf(excess.getItemCode(), MrpSuggestion.TYPE_EXCESS);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.confirm(id, null, null));
        assertEquals(422, ex.getCode(), "EXCESS 不可确认 422");
        assertTrue(ex.getMessage().contains("过量"), "提示过量：" + ex.getMessage());
    }

    // ---------- 6.4 异常处置 ----------

    @Test
    void handleExcessWithRequiredNote() {
        String id = idOf(excess.getItemCode(), MrpSuggestion.TYPE_EXCESS);
        // 备注必填
        ServiceException noNote = assertThrows(ServiceException.class,
                () -> planService.handle(id, " "));
        assertEquals(422, noNote.getCode(), "处理备注必填 422");

        planService.handle(id, "已联系采购推迟在途订单");
        Map<String, Object> row = planService.suggestions(MrpSuggestion.TYPE_EXCESS, null, null,
                excess.getItemCode(), false).get(0);
        assertEquals("1", row.get("handledFlag"), "已处理标记");
        assertEquals("已联系采购推迟在途订单", row.get("handledNote"), "备注留痕");
        assertEquals("planner-tester", row.get("handledBy"), "处理人留痕");
        assertTrue(String.valueOf(row.get("handledAt")).length() > 0, "处理时间留痕");
    }

    @Test
    void nonExceptionRowCannotBeHandled() {
        String id = idOf(normal.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.handle(id, "随意标记"));
        assertEquals(422, ex.getCode(), "非异常行拒绝 422");
        assertTrue(ex.getMessage().contains("过量供给/逾期"), "提示仅异常可处置：" + ex.getMessage());
    }
}
