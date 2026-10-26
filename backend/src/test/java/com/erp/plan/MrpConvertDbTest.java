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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转正出口（change add-mrp-demand-planning，任务 6.2/6.3）：
 * 采购转正复用请购链路（prNo 落库/停用物料整批阻断透传/状态门）；
 * 生产转正 PMO 占位（按日流水唯一 + 计数冲突重试）。
 */
@SpringBootTest
class MrpConvertDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        cleanupPr(jdbc);
        PlanSeed.cleanup(jdbc);
        PlanTestAuth.login("planner-tester", "ROLE_PLANNER");
        categoryCode = PlanSeed.mkCategory(jdbc);
    }

    @AfterEach
    void tearDown() {
        PlanTestAuth.logout();
        cleanupPr(jdbc);
        PlanSeed.cleanup(jdbc);
    }

    /** 清理本测试创建的 PR（CREATE_BY=planner-tester）+ PR 事件（幂等键防撞；
     *  先清事件再删头，且不依赖头存在——上次中断可能已留下孤儿 outbox 行） */
    private static void cleanupPr(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'PR-%'");
        jdbc.update("DELETE FROM erp_proc_pr_line WHERE PR_ID IN " +
                "(SELECT ID FROM erp_proc_requisition WHERE CREATE_BY='planner-tester')");
        jdbc.update("DELETE FROM erp_proc_requisition WHERE CREATE_BY='planner-tester'");
    }

    private MdmItem mk(String name, String type) {
        return PlanSeed.mkItem(itemService, categoryCode, name, type);
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

    private String targetOf(String id) {
        return jdbc.queryForObject("SELECT TARGET_NO FROM erp_mrp_suggestion WHERE ID=?",
                String.class, id);
    }

    // ---------- 6.2 采购转正 ----------

    @Test
    void convertPrCreatesPrAndMarksConverted() {
        MdmItem buy = mk("转正采购件P", "BUY");
        PlanSeed.mkSo(jdbc, buy.getItemCode(), "30", "0", LocalDate.now().plusDays(12));
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        String id = idOf(buy.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        planService.confirm(id, null, null);

        Map<String, Object> out = planService.convertPr(List.of(id));

        String prNo = String.valueOf(out.get("targetNo"));
        assertTrue(prNo.startsWith("PR-"), "PR 单号：" + prNo);
        assertEquals(MrpSuggestion.ST_CONVERTED, statusOf(id), "建议置已转换");
        assertEquals(prNo, targetOf(id), "TARGET_NO=PR 单号");
        // PR 落库（复用链路产物）
        Integer prCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_proc_requisition WHERE PR_NO=? AND SOURCE_TYPE='MRP'",
                Integer.class, prNo);
        assertEquals(1, prCount, "PR 头落库且来源 MRP");
        Integer lineQty = jdbc.queryForObject(
                "SELECT QTY FROM erp_proc_pr_line WHERE PR_ID=(SELECT ID FROM erp_proc_requisition WHERE PR_NO=?)",
                Integer.class, prNo);
        assertEquals(0, new BigDecimal(lineQty).compareTo(new BigDecimal("30")), "PR 行数量=确认量 30");
    }

    @Test
    void convertPrBlockedByInactiveItemEntireBatch() {
        MdmItem buy = mk("转正停用件Q", "BUY");
        PlanSeed.mkSo(jdbc, buy.getItemCode(), "20", "0", LocalDate.now().plusDays(12));
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        String id = idOf(buy.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        planService.confirm(id, null, null);
        // 确认后物料被停用（状态漂移）
        jdbc.update("UPDATE erp_mdm_item SET STATUS='0' WHERE ITEM_CODE=?", buy.getItemCode());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.convertPr(List.of(id)));
        assertEquals(422, ex.getCode(), "整批阻断 422");
        assertTrue(ex.getMessage().contains("整批阻断"), "透传整批阻断语义：" + ex.getMessage());
        // 建议状态不变（未转换）、无 PR 生成
        assertEquals(MrpSuggestion.ST_CONFIRMED, statusOf(id), "失败不改状态");
        Integer prs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_proc_requisition WHERE CREATE_BY='planner-tester'",
                Integer.class);
        assertEquals(0, prs, "阻断时无 PR 落库");
    }

    @Test
    void convertPrRequiresConfirmedStatus() {
        MdmItem buy = mk("转正状态门件R", "BUY");
        PlanSeed.mkSo(jdbc, buy.getItemCode(), "15", "0", LocalDate.now().plusDays(12));
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        String id = idOf(buy.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        // 未确认直接转正 → 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.convertPr(List.of(id)));
        assertEquals(422, ex.getCode(), "状态门 422");
        assertTrue(ex.getMessage().contains("已确认"), "提示须已确认：" + ex.getMessage());
    }

    // ---------- 6.3 生产转正 ----------

    @Test
    void convertMoCreatesUniquePmoPerBatch() {
        MdmItem make1 = mk("转正自制件M1", "MAKE");
        MdmItem make2 = mk("转正自制件M2", "MAKE");
        PlanSeed.mkSo(jdbc, make1.getItemCode(), "10", "0", LocalDate.now().plusDays(12));
        PlanSeed.mkSo(jdbc, make2.getItemCode(), "12", "0", LocalDate.now().plusDays(12));
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        String id1 = idOf(make1.getItemCode(), MrpSuggestion.TYPE_PRODUCTION);
        String id2 = idOf(make2.getItemCode(), MrpSuggestion.TYPE_PRODUCTION);
        planService.confirm(id1, null, null);
        planService.confirm(id2, null, null);

        Map<String, Object> out1 = planService.convertMo(List.of(id1));
        Map<String, Object> out2 = planService.convertMo(List.of(id2));

        String pmo1 = String.valueOf(out1.get("targetNo"));
        String pmo2 = String.valueOf(out2.get("targetNo"));
        assertTrue(pmo1.matches("PMO-\\d{8}-\\d{3}"), "PMO 格式：" + pmo1);
        assertNotEquals(pmo1, pmo2, "按日流水唯一");
        assertEquals(MrpSuggestion.ST_CONVERTED, statusOf(id1), "已转换");
        assertEquals(pmo1, targetOf(id1), "TARGET_NO 落库");
    }

    @Test
    void convertMoRetriesOnNumberClash() {
        MdmItem make = mk("转正冲突自制件M3", "MAKE");
        PlanSeed.mkSo(jdbc, make.getItemCode(), "8", "0", LocalDate.now().plusDays(12));
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        String id = idOf(make.getItemCode(), MrpSuggestion.TYPE_PRODUCTION);
        planService.confirm(id, null, null);

        // 预置同日前缀占位 001 与 003（制造计数与候选冲突 → 触发递增重试）
        String day = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        jdbc.update("INSERT INTO erp_mrp_suggestion (ID, RUN_ID, ITEM_CODE, TYPE, STATUS, TARGET_NO) " +
                        "VALUES ('sg-fake-001','run-fake','FAKE-ITEM','PRODUCTION','CONVERTED',?)",
                "PMO-" + day + "-001");
        jdbc.update("INSERT INTO erp_mrp_suggestion (ID, RUN_ID, ITEM_CODE, TYPE, STATUS, TARGET_NO) " +
                        "VALUES ('sg-fake-003','run-fake','FAKE-ITEM','PRODUCTION','CONVERTED',?)",
                "PMO-" + day + "-003");

        Map<String, Object> out = planService.convertMo(List.of(id));
        // count=2 → 候选 003 冲突 → 重试至 004
        assertEquals("PMO-" + day + "-004", out.get("targetNo"), "计数冲突递增重试至 004");
    }

    @Test
    void convertTypeGate() {
        MdmItem buy = mk("类型门采购件T", "BUY");
        PlanSeed.mkSo(jdbc, buy.getItemCode(), "6", "0", LocalDate.now().plusDays(12));
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        String id = idOf(buy.getItemCode(), MrpSuggestion.TYPE_PURCHASE);
        planService.confirm(id, null, null);
        // 采购建议走生产转正 → 类型不符 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.convertMo(List.of(id)));
        assertEquals(422, ex.getCode(), "类型门 422");
        assertTrue(ex.getMessage().contains("类型不符"), "提示类型不符：" + ex.getMessage());
    }
}
