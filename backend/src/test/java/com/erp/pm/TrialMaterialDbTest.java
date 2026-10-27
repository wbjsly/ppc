package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmMaterialIssueService;
import com.erp.service.pm.PmTrialConsumptionService;
import com.erp.service.pm.PmTrialCostService;
import com.erp.service.pm.PmTrialReturnService;
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
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 研发物料试制 DbTest（rnd-material spec：WBS 类型 L1/审核人 RD_LEAD/库存不足/
 * 消耗确认防重+差异 L4+样品台账/退料 REUSABLE 冲减+SCRAP 保留+跨项目 L1+结余超限/
 * 试制成本按 TRIAL 聚合）。造数前缀 trl-，物料 TRL-ITEM。
 */
@SpringBootTest
class TrialMaterialDbTest {

    @Autowired
    private PmMaterialIssueService matIssueService;
    @Autowired
    private PmTrialConsumptionService consumptionService;
    @Autowired
    private PmTrialReturnService returnService;
    @Autowired
    private PmTrialCostService costService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String trialTaskId;
    private String devTaskId;

    @BeforeEach
    void setUp() {
        purge();
        projectId = seedProject();
        trialTaskId = seedTask("trl-phase", "TRL1T", "TRL 试制任务", "TRIAL");
        devTaskId = seedTask("trl-phase", "TRL1D", "TRL 开发任务", "DEV");
        seedStock("TRL-ITEM", "TRL-B1", 100);
        authAs("trl-rd", "RD_LEAD");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ==================== 8.1 试制领料校验 ====================

    @Test
    void trialIssueNonTrialTaskBlockedL1() {
        // 试制领料关联 DEV 任务 → C-4.8-12 L1
        ServiceException e = assertThrows(ServiceException.class,
                () -> matIssueService.save(issuePayload(devTaskId, 10), true));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("TRIAL"), e.getMessage());
    }

    @Test
    void trialIssueTrialTaskPassesAndDeductsStock() {
        Map<String, Object> saved = matIssueService.save(issuePayload(trialTaskId, 10), true);
        String issueId = String.valueOf(saved.get("id"));
        assertEquals("1", jdbc.queryForObject(
                "SELECT TRIAL_FLAG FROM erp_pm_mat_issue WHERE ID=?", String.class, issueId));
        BigDecimal avail = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='TRL-ITEM' AND BATCH_NO='TRL-B1'",
                BigDecimal.class);
        assertEquals(0, avail.compareTo(new BigDecimal("90")), "试制领料提交扣库存");
    }

    @Test
    void trialIssueReviewerMustBeRdLead() {
        // RD_LEAD 提交，PM 审核 → 403
        Map<String, Object> saved = matIssueService.save(issuePayload(trialTaskId, 5), true);
        String issueId = String.valueOf(saved.get("id"));
        authAs("trl-pm", "PM");
        ServiceException e = assertThrows(ServiceException.class,
                () -> matIssueService.review(issueId, Map.of("approved", true)));
        assertEquals(403, e.getCode());
    }

    @Test
    void trialIssueInsufficientStockBlocked() {
        // 库存 100，领 500 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> matIssueService.save(issuePayload(trialTaskId, 500), true));
        assertEquals(422, e.getCode());
    }

    // ==================== 8.2 消耗确认 + 样品台账 ====================

    @Test
    void consumptionConfirmBlockedWithoutApprovedIssue() {
        // 未发料（SUBMITTED 态）不可确认
        Map<String, Object> saved = matIssueService.save(issuePayload(trialTaskId, 10), true);
        String issueId = String.valueOf(saved.get("id"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> consumptionService.confirm(consumptionPayload(issueId, 8, null)));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("发料") || e.getMessage().contains("APPROVED"), e.getMessage());
    }

    @Test
    void consumptionConfirmComputesSurplusAndSampleLedger() {
        String issueId = approvedIssue(10);
        Map<String, Object> out = consumptionService.confirm(consumptionPayload(issueId, 8, null));
        assertEquals(new BigDecimal("2.000"), new BigDecimal(String.valueOf(out.get("totalSurplus"))));
        // 样品台账批次关联（BR-4.8-39）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_sample_ledger WHERE ISSUE_ID=? AND BATCH_NO='TRL-B1'",
                Integer.class, issueId), "样品台账与领料批次关联");
    }

    @Test
    void consumptionDuplicateBlocked409() {
        String issueId = approvedIssue(10);
        consumptionService.confirm(consumptionPayload(issueId, 8, null));
        ServiceException e = assertThrows(ServiceException.class,
                () -> consumptionService.confirm(consumptionPayload(issueId, 8, null)));
        assertEquals(409, e.getCode(), "一领料单一份确认");
    }

    @Test
    void consumptionDeviationOver10pctL4() {
        String issueId = approvedIssue(10);
        // 实耗 5（差异 50%）且无说明 → l4Warning
        Map<String, Object> out = consumptionService.confirm(consumptionPayload(issueId, 5, null));
        assertNotNull(out.get("l4Warning"), "差异>10% 无说明 L4");
    }

    // ==================== 8.3 结余退库与成本冲减 ====================

    @Test
    void returnReusableReceivesStockAndReducesCost() {
        String issueId = approvedIssueWithConsumption(10, 8); // 结余 2
        Map<String, Object> saved = returnService.save(returnPayload(issueId, "TRL-ITEM", "TRL-B1", 2, "REUSABLE"), true);
        String rid = String.valueOf(saved.get("id"));
        BigDecimal before = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='TRL-ITEM' AND BATCH_NO='TRL-B1'",
                BigDecimal.class); // 90
        returnService.review(rid, Map.of("approved", true));
        BigDecimal after = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='TRL-ITEM' AND BATCH_NO='TRL-B1'",
                BigDecimal.class);
        assertEquals(0, after.compareTo(before.add(new BigDecimal("2"))), "REUSABLE 入库加回");
        // 负向成本冲减（单价 25 × 2 = 50 → -50）
        BigDecimal neg = jdbc.queryForObject(
                "SELECT SUM(AMOUNT) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='TRIAL_RETURN' AND PROJECT_ID=?",
                BigDecimal.class, projectId);
        assertEquals(0, neg.compareTo(new BigDecimal("-50.00")), "负向成本冲减=" + neg);
    }

    @Test
    void returnScrapKeepsCostNoStock() {
        String issueId = approvedIssueWithConsumption(10, 8);
        Map<String, Object> saved = returnService.save(returnPayload(issueId, "TRL-ITEM", "TRL-B1", 2, "SCRAP"), true);
        String rid = String.valueOf(saved.get("id"));
        BigDecimal before = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='TRL-ITEM' AND BATCH_NO='TRL-B1'",
                BigDecimal.class);
        returnService.review(rid, Map.of("approved", true));
        BigDecimal after = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='TRL-ITEM' AND BATCH_NO='TRL-B1'",
                BigDecimal.class);
        assertEquals(0, after.compareTo(before), "SCRAP 不入库");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='TRIAL_RETURN'", Integer.class),
                "SCRAP 不冲减成本");
    }

    @Test
    void returnCrossProjectBatchBlockedL1() {
        String issueId = approvedIssueWithConsumption(10, 8);
        // 批次非原领料批次 → 跨项目/外来 L1
        ServiceException e = assertThrows(ServiceException.class,
                () -> returnService.save(returnPayload(issueId, "TRL-ITEM", "FOREIGN-BATCH", 1, "REUSABLE"), true));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("BR-4.8-04") || e.getMessage().contains("挪用"), e.getMessage());
    }

    @Test
    void returnExceedsSurplusBlocked() {
        String issueId = approvedIssueWithConsumption(10, 8); // 结余 2
        ServiceException e = assertThrows(ServiceException.class,
                () -> returnService.save(returnPayload(issueId, "TRL-ITEM", "TRL-B1", 5, "REUSABLE"), true));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("结余") || e.getMessage().contains("超过"), e.getMessage());
    }

    // ==================== 8.4 试制成本聚合 ====================

    @Test
    void trialCostAggregatesByTrialTask() {
        // 建 TRAIL 预算行 + 在 trial 任务上归集材料 100
        jdbc.update("INSERT INTO erp_pm_budget_line (ID, PROJECT_ID, WBS_ID, LABOR, MATERIAL, "
                + "SUBCONTRACT, TRAVEL, OTHER, INDIRECT, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('trl-bl', ?, ?, 0, 200, 0, 0, 0, 0, 'junit', NOW(), '0', 0)", projectId, trialTaskId);
        jdbc.update("INSERT INTO erp_pm_cost_collect (ID, PROJECT_ID, WBS_ID, COST_TYPE, AMOUNT, "
                + "SOURCE_TYPE, SOURCE_ID, COLLECT_DATE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('trl-cc', ?, ?, 'MATERIAL', 100, 'MAT_ISSUE', 'trl-x', CURDATE(), 'junit', NOW(), '0', 0)",
                projectId, trialTaskId);
        Map<String, Object> s = costService.summary(projectId);
        assertEquals(0, new BigDecimal(String.valueOf(s.get("material"))).compareTo(new BigDecimal("100")),
                "试制材料聚合");
        assertEquals(0, new BigDecimal(String.valueOf(s.get("budget"))).compareTo(new BigDecimal("200")),
                "试制预算比对");
        // 偏差率 = (100-200)/200 = -50%
        assertEquals(0, new BigDecimal(String.valueOf(s.get("deviationRate")))
                .compareTo(new BigDecimal("-50.00")), "偏差率");
    }

    // ==================== helpers ====================

    private Map<String, Object> issuePayload(String wbsId, int qty) {
        return Map.of("projectId", projectId,
                "issueDate", LocalDate.now().toString(),
                "trialFlag", "1",
                "trialPurpose", "TRL 试制样件验证",
                "lines", List.of(Map.of(
                        "wbsId", wbsId, "itemId", "TRL-ITEM", "itemName", "TRL 物料",
                        "batchNo", "TRL-B1", "warehouseCode", "WH-TRL",
                        "qty", qty, "unitPrice", 25)));
    }

    /** 已发料试制领料单（RD_LEAD 审核通过） */
    private String approvedIssue(int qty) {
        authAs("trl-rd", "RD_LEAD");
        Map<String, Object> saved = matIssueService.save(issuePayload(trialTaskId, qty), true);
        String id = String.valueOf(saved.get("id"));
        matIssueService.review(id, Map.of("approved", true));
        return id;
    }

    /** 已发料 + 已消耗确认（实耗 actual，结余 qty-actual） */
    private String approvedIssueWithConsumption(int qty, int actual) {
        String id = approvedIssue(qty);
        consumptionService.confirm(consumptionPayload(id, actual, null));
        return id;
    }

    private Map<String, Object> consumptionPayload(String issueId, int actualQty, String note) {
        return Map.of("issueId", issueId,
                "lines", List.of(Map.of("itemId", "TRL-ITEM", "actualQty", actualQty,
                        "deviationNote", note == null ? "" : note)),
                "samples", List.of(Map.of("name", "TRL 样品", "itemId", "TRL-ITEM",
                        "qty", 1, "destination", "CUSTOMER")));
    }

    private Map<String, Object> returnPayload(String issueId, String itemId, String batchNo,
                                              int qty, String qc) {
        return Map.of("issueId", issueId,
                "lines", List.of(Map.of("itemId", itemId, "batchNo", batchNo,
                        "returnQty", qty, "qcResult", qc)));
    }

    private String seedProject() {
        String id = "trl-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'TRL 试制项目', '研发', 'INTERNAL', 'NS-TRL', 'trl-pm', "
                + "DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                + "'ACTIVE', '1', 1000, 0.10, '0', '0', 'junit', NOW(), '0', 0)", id);
        return id;
    }

    private String seedTask(String phaseId, String code, String name, String taskType) {
        // 阶段仅第一次建
        Integer phase = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_wbs WHERE ID=?", Integer.class, phaseId);
        if (phase == null || phase == 0) {
            jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                    + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'TRL1PH', 1, 'TRL 阶段', "
                    + "'junit', NOW(), '0', 0)", phaseId, projectId);
        }
        String taskId = "trl-task-" + code;
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, "
                + "OWNER_USER_ID, OWNER_GRADE, TASK_TYPE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, 3, ?, ?, 'trl-rd', 'MID', ?, 'junit', NOW(), '0', 0)",
                taskId, projectId, code, name, phaseId, taskType);
        return taskId;
    }

    private void seedStock(String item, String batch, int qty) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES ('trl-stock-1', 'WH-TRL', ?, 'TRL 物料', ?, '', ?, ?, 'junit', NOW(), '0', 0)",
                item, batch, qty, qty);
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, "x",
                java.util.Arrays.stream(roles)
                        .map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                        .toList()));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pm_sample_ledger WHERE ISSUE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_trial_consumption WHERE PROJECT_ID LIKE 'trl-%') t)");
        jdbc.update("DELETE FROM erp_pm_trial_consumption_line WHERE CONSUMPTION_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_trial_consumption WHERE PROJECT_ID LIKE 'trl-%') t)");
        jdbc.update("DELETE FROM erp_pm_trial_consumption WHERE PROJECT_ID LIKE 'trl-%'");
        jdbc.update("DELETE FROM erp_pm_trial_return_line WHERE RETURN_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_trial_return WHERE PROJECT_ID LIKE 'trl-%') t)");
        jdbc.update("DELETE FROM erp_pm_trial_return WHERE PROJECT_ID LIKE 'trl-%'");
        jdbc.update("DELETE FROM erp_pm_mat_issue_line WHERE ISSUE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_mat_issue WHERE PROJECT_ID LIKE 'trl-%') t)");
        jdbc.update("DELETE FROM erp_pm_mat_issue WHERE PROJECT_ID LIKE 'trl-%'");
        jdbc.update("DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID LIKE 'trl-%'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_DOC_NO LIKE '%TRL%' OR SOURCE_DOC_NO LIKE '%trl%'");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID LIKE 'trl-%'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'trl-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'trl-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE='TRL-ITEM'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE='TRL-ITEM'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID IN ('trl-rd', 'trl-pm')");
    }
}
