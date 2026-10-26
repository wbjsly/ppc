package com.erp.plan;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpRun;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.MrpPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行编排（change add-mrp-demand-planning，任务 5.1/5.2/5.3）：
 * 范围过滤与 RUN_NO/统计、并发互斥、重跑取代、运行历史。
 */
@SpringBootTest
class MrpRunDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem a;
    private MdmItem b;

    @BeforeEach
    void seed() {
        PlanSeed.cleanup(jdbc);
        PlanTestAuth.login("planner-tester", "ROLE_PLANNER");
        categoryCode = PlanSeed.mkCategory(jdbc);
        a = PlanSeed.mkItem(itemService, categoryCode, "运行范围甲", "BUY");
        b = PlanSeed.mkItem(itemService, categoryCode, "运行范围乙", "BUY");
        PlanSeed.mkSo(jdbc, a.getItemCode(), "40", "0", LocalDate.now().plusDays(9));
        PlanSeed.mkSo(jdbc, b.getItemCode(), "60", "0", LocalDate.now().plusDays(9));
    }

    @AfterEach
    void tearDown() {
        PlanTestAuth.logout();
        PlanSeed.cleanup(jdbc);
    }

    @Test
    void runWithScopeStatsAndRunNo() {
        Map<String, Object> run = planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        assertEquals(MrpRun.ST_DONE, run.get("runStatus"), "运行完成");

        // RUN_NO 流水格式
        String runNo = String.valueOf(run.get("runNo"));
        String expectPrefix = "RUN-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        assertTrue(runNo.startsWith(expectPrefix), "RUN_NO 前缀：" + runNo);

        // 统计落库（扫描=RPLAN 组物料 2 个；建议 2 行）
        assertEquals(2, ((Number) run.get("statScanned")).intValue(), "扫描物料数=范围过滤结果");
        assertEquals(2, ((Number) run.get("statSuggested")).intValue(), "建议行数");
        assertEquals(0, ((Number) run.get("statException")).intValue(), "无异常");

        // 建议只来自范围（GROUP 过滤生效）
        Integer foreign = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_suggestion s WHERE s.ITEM_CODE NOT IN (?,?)",
                Integer.class, a.getItemCode(), b.getItemCode());
        assertEquals(0, foreign, "范围外物料无建议");
    }

    @Test
    void concurrentRunBlocked() {
        // 直插 RUNNING 占位（互斥位）
        jdbc.update("INSERT INTO erp_mrp_run (ID, RUN_NO, SCOPE_TYPE, RUN_STATUS) " +
                "VALUES ('run-mutex-test','RUN-MUTEX-001','FULL','RUNNING')");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE));
        assertEquals(422, ex.getCode(), "互斥 422");
        assertTrue(ex.getMessage().contains("进行中"), "提示已有运行：" + ex.getMessage());
    }

    @Test
    void rerunSupersedesOldActiveSuggestions() {
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        String firstId = jdbc.queryForObject(
                "SELECT ID FROM erp_mrp_suggestion WHERE ITEM_CODE=? AND STATUS='PENDING'",
                String.class, a.getItemCode());

        Map<String, Object> second = planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        String secondRunNo = String.valueOf(second.get("runNo"));

        // 旧建议被取代且记录取代者
        String oldStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_mrp_suggestion WHERE ID=?", String.class, firstId);
        assertEquals(MrpSuggestionStatus.SUPERSEDED, oldStatus, "旧建议 SUPERSEDED");
        String byRun = jdbc.queryForObject(
                "SELECT SUPERSEDED_BY_RUN FROM erp_mrp_suggestion WHERE ID=?", String.class, firstId);
        assertEquals(secondRunNo, byRun, "记录取代运行号");

        // 被取代行不可再确认（状态门）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.confirm(firstId, null, null));
        assertEquals(422, ex.getCode(), "被取代不可确认 422");

        // 新建议为唯一活跃
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_suggestion WHERE ITEM_CODE=? AND STATUS='PENDING'",
                Integer.class, a.getItemCode());
        assertEquals(1, active, "每物料每类型仅一个活跃建议");
    }

    @Test
    void runHistoryListed() {
        planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        List<Map<String, Object>> history = planService.runs();
        assertTrue(history.stream().anyMatch(r ->
                        String.valueOf(r.get("runNo")).startsWith("RUN-")
                                && r.containsKey("statScanned")
                                && r.containsKey("scopeType")
                                && MrpRun.ST_DONE.equals(r.get("runStatus"))),
                "历史含 RUN_NO/范围/统计字段：" + history.size());
        assertNotNull(history.get(0).get("runBy"), "运行人留痕");
    }

    /** 状态常量本地别名（避免测试内长引用） */
    private static final class MrpSuggestionStatus {
        static final String SUPERSEDED = "SUPERSEDED";
    }
}
