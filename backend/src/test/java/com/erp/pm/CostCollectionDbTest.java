package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.service.pm.PmMaterialIssueService;
import com.erp.service.pm.PmSubcontractService;
import com.erp.service.pm.PmTravelClaimService;
import com.erp.service.pm.PmCostCollectService;
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
 * 成本归集 DbTest（cost-collection-freeze spec：领料 WBS 硬阻断+扣库存 / 外协余额阻断+回写 /
 * 差旅 DEPT_ONLY+超标 L2 / 五类凭证 SOURCE_TYPE=PROJECT_COST）。
 * 造数前缀 ccf- / 项目名 CCF%。
 */
@SpringBootTest
class CostCollectionDbTest {

    @Autowired
    private PmMaterialIssueService matIssueService;
    @Autowired
    private PmSubcontractService subcontractService;
    @Autowired
    private PmTravelClaimService travelService;
    @Autowired
    private PmCostCollectService costCollectService;
    @Autowired
    private JdbcTemplate jdbc;

    private String projectId;
    private String taskId;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("ccf-emp", "ccfemp", "CCF 测试员工");
        projectId = seedProject("CCF 归集项目", "2026-01-01", "2026-12-31");
        taskId = seedTask("CCF 任务");
        seedStock("CCF-ITEM-1", "CCF-B1", 100);
        authAs("ccf-emp", "USER");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ---------- 领料（FR-4.8-5-1 / BR-4.8-20） ----------

    @Test
    void materialIssueWbsHardBlock() {
        Map<String, Object> payload = matPayload(null, null); // 行无 wbsId
        ServiceException e = assertThrows(ServiceException.class,
                () -> matIssueService.save(payload, false));
        assertTrue(e.getMessage().contains("WBS"), e.getMessage());
        assertEquals(422, e.getCode());
    }

    @Test
    void materialIssueSubmitDeductsStockAndReviewCollects() {
        Map<String, Object> saved = matIssueService.save(matPayload(taskId, "CCF-B1"), true);
        String issueId = String.valueOf(saved.get("id"));
        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_mat_issue WHERE ID=?", String.class, issueId));
        // 库存已扣 10 个
        BigDecimal avail = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE='CCF-ITEM-1' AND BATCH_NO='CCF-B1'",
                BigDecimal.class);
        assertEquals(0, avail.compareTo(new BigDecimal("90")), "提交即扣库存，实际=" + avail);
        // 审核通过 → 归集 MATERIAL + 凭证
        matIssueService.review(issueId, Map.of("approved", true));
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_pm_mat_issue WHERE ID=?", String.class, issueId));
        Integer collect = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='MAT_ISSUE' "
                        + "AND COST_TYPE='MATERIAL' AND PROJECT_ID=?", Integer.class, projectId);
        assertEquals(1, collect, "按明细行归集一条材料成本");
        BigDecimal amount = jdbc.queryForObject(
                "SELECT AMOUNT FROM erp_pm_cost_collect WHERE SOURCE_TYPE='MAT_ISSUE' AND PROJECT_ID=?",
                BigDecimal.class, projectId);
        assertEquals(0, amount.compareTo(new BigDecimal("250.00")), "10 × 25 = 250，实际=" + amount);
        // 凭证（SOURCE_TYPE=PROJECT_COST）
        Integer vouchers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST'",
                Integer.class);
        assertTrue(vouchers >= 1, "归集过账生成会计凭证");
    }

    @Test
    void frozenProjectBlocksMaterialIssue() {
        jdbc.update("UPDATE erp_pm_project SET FREEZE_FLAG='1', FREEZE_REASON='CCF 测试冻结' WHERE ID=?", projectId);
        ServiceException e = assertThrows(ServiceException.class,
                () -> matIssueService.save(matPayload(taskId, "CCF-B1"), true));
        assertTrue(e.getMessage().contains("冻结"), e.getMessage());
    }

    // ---------- 外协（FR-4.8-5-2） ----------

    @Test
    void subcontractOverBalanceBlockedAndReviewWritesBack() {
        String contractId = seedContract(projectId, taskId, 10000);
        // 超余额付款 → 422
        ServiceException e = assertThrows(ServiceException.class, () -> subcontractService.savePayment(
                Map.of("contractId", contractId, "amount", 12000,
                        "payDate", LocalDate.now().toString()), true));
        assertTrue(e.getMessage().contains("超出合同剩余余额"), e.getMessage());
        // 8000 提交 + 审核通过 → 归集 + 回写 PAID_AMOUNT
        Map<String, Object> pay = subcontractService.savePayment(
                Map.of("contractId", contractId, "amount", 8000,
                        "payDate", LocalDate.now().toString()), true);
        subcontractService.reviewPayment(String.valueOf(pay.get("id")), Map.of("approved", true));
        BigDecimal paid = jdbc.queryForObject(
                "SELECT PAID_AMOUNT FROM erp_pm_subcontract WHERE ID=?", BigDecimal.class, contractId);
        assertEquals(0, paid.compareTo(new BigDecimal("8000")), "已付累计回写，实际=" + paid);
        Integer collect = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='SUB_PAYMENT' AND PROJECT_ID=?",
                Integer.class, projectId);
        assertEquals(1, collect, "外协成本归集一条");
        // 再付 3000（超剩余 2000）→ 422
        ServiceException e2 = assertThrows(ServiceException.class, () -> subcontractService.savePayment(
                Map.of("contractId", contractId, "amount", 3000,
                        "payDate", LocalDate.now().toString()), true));
        assertTrue(e2.getMessage().contains("超出合同剩余余额"), e2.getMessage());
    }

    // ---------- 差旅（FR-4.8-5-3） ----------

    @Test
    void travelDeptOnlySkipsProjectCollect() {
        Map<String, Object> saved = travelService.save(Map.of(
                "grade", "JUNIOR",
                "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().toString(),
                "amount", 300), true);
        String id = String.valueOf(saved.get("id"));
        assertEquals("1", jdbc.queryForObject(
                "SELECT DEPT_ONLY FROM erp_pm_travel_claim WHERE ID=?", String.class, id));
        travelService.review(id, Map.of("approved", true));
        Integer collect = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='TRAVEL_CLAIM'",
                Integer.class);
        assertEquals(0, collect, "未挂项目不进项目成本（归部门费用）");
    }

    @Test
    void travelOverStandardRequiresReasonAndAutoL2() {
        // JUNIOR 日限额最高档 350；2 天报销 2000 → 超标
        ServiceException e = assertThrows(ServiceException.class, () -> travelService.save(Map.of(
                "grade", "JUNIOR", "projectId", projectId,
                "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().plusDays(1).toString(),
                "amount", 2000), true));
        assertTrue(e.getMessage().contains("超标原因"), e.getMessage());
        // 补原因 → 提交自动发起 L2 单节点财务审批
        Map<String, Object> saved = travelService.save(Map.of(
                "grade", "JUNIOR", "projectId", projectId,
                "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().plusDays(1).toString(),
                "amount", 2000, "overReason", "客户驻场住宿超标准"), true);
        String id = String.valueOf(saved.get("id"));
        String apprId = jdbc.queryForObject(
                "SELECT OVER_APPR_ID FROM erp_pm_travel_claim WHERE ID=?", String.class, id);
        assertNotNull(apprId, "超标单据自动发起 L2 审批");
        assertEquals("PmTravelOver", jdbc.queryForObject(
                "SELECT BIZ_TYPE FROM erp_sys_approval WHERE ID=?", String.class, apprId));
        // 常规审核被拒（须走 L2）
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> travelService.review(id, Map.of("approved", true)));
        assertTrue(e2.getMessage().contains("L2"), e2.getMessage());
    }

    @Test
    void travelWithinStandardReviewCollects() {
        // JUNIOR 一天 300 ≤ 350 → 标准内
        Map<String, Object> saved = travelService.save(Map.of(
                "grade", "JUNIOR", "projectId", projectId,
                "startDate", LocalDate.now().toString(),
                "endDate", LocalDate.now().toString(),
                "amount", 300), true);
        travelService.review(String.valueOf(saved.get("id")), Map.of("approved", true));
        Integer collect = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE SOURCE_TYPE='TRAVEL_CLAIM' AND PROJECT_ID=?",
                Integer.class, projectId);
        assertEquals(1, collect, "标准内报销审核通过归集差旅成本");
    }

    // ---------- 五类凭证统一入口（design D2） ----------

    @Test
    void fiveTypeCollectsGenerateProjectCostVouchers() {
        // 五类逐一归集 → 各生成 SOURCE_TYPE=PROJECT_COST 凭证
        costCollectService.collect(PmCostCollectService.TYPE_LABOR,
                PmCostCollectService.SRC_HOURS_COLLECT, "ccf-hc-1", projectId, taskId,
                new BigDecimal("300"), LocalDate.now());
        costCollectService.collect(PmCostCollectService.TYPE_INDIRECT,
                PmCostCollectService.SRC_ALLOC, "ccf-al-1", projectId, null,
                new BigDecimal("120"), LocalDate.now());
        Integer vouchers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST' "
                        + "AND SOURCE_DOC_NO IN ('HOURS_COLLECT:ccf-hc-1', 'ALLOC:ccf-al-1')",
                Integer.class);
        assertEquals(2, vouchers, "两类归集各一张凭证");
        // 幂等：同来源重复归集不产生第二行
        costCollectService.collect(PmCostCollectService.TYPE_LABOR,
                PmCostCollectService.SRC_HOURS_COLLECT, "ccf-hc-1", projectId, taskId,
                new BigDecimal("300"), LocalDate.now());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_pm_cost_collect WHERE SOURCE_ID='ccf-hc-1'", Integer.class);
        assertEquals(1, rows, "同来源幂等不重复归集");
    }

    // ---------- helpers ----------

    private Map<String, Object> matPayload(String wbsId, String batchNo) {
        return Map.of("projectId", projectId,
                "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of(
                        "wbsId", wbsId == null ? "" : wbsId,
                        "itemId", "CCF-ITEM-1",
                        "itemName", "CCF 测试物料",
                        "batchNo", batchNo == null ? "" : batchNo,
                        "warehouseCode", "WH-CCF",
                        "qty", 10, "unitPrice", 25)));
    }

    private String seedContract(String pid, String wbsId, int total) {
        String id = "ccf-sc-1";
        jdbc.update("INSERT INTO erp_pm_subcontract (ID, CONTRACT_NO, PROJECT_ID, WBS_ID, NO_WBS_FLAG, "
                + "SUPPLIER_NAME, TOTAL_AMOUNT, PAID_AMOUNT, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'PSC20261026001', ?, ?, '0', 'CCF 供应商', ?, 0, 'ACTIVE', 'junit', NOW(), '0', 0)",
                id, pid, wbsId, BigDecimal.valueOf(total));
        return id;
    }

    private String seedProject(String name, String start, String end) {
        String id = "ccf-prj-1";
        jdbc.update("INSERT INTO erp_pm_project (ID, NAME, PROJ_TYPE, DELIVERY_PATH, INTERNAL_FORM_NO, "
                + "PM_USER_ID, START_DATE, END_DATE, STATUS, BASELINE_LOCKED, BASELINE_AMOUNT, "
                + "FENPLAN_RATE, FREEZE_FLAG, START_NOTIFIED, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, '研发', 'INTERNAL', 'NS-CCF', 'ccf-emp', ?, ?, 'ACTIVE', '0', 0, "
                + "0.10, '0', '0', 'junit', NOW(), '0', 0)", id, name, start, end);
        return id;
    }

    private String seedTask(String name) {
        String phase = "ccf-wbs-phase";
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'CCF1PH', 1, 'CCF 阶段', "
                + "'junit', NOW(), '0', 0)", phase, projectId);
        String id = "ccf-wbs-task";
        jdbc.update("INSERT INTO erp_pm_wbs (ID, PROJECT_ID, WBS_CODE, LEVEL, NAME, PARENT_ID, "
                + "OWNER_USER_ID, OWNER_GRADE, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'CCF1T', 3, ?, ?, 'ccf-emp', 'JUNIOR', 'junit', NOW(), '0', 0)",
                id, projectId, name, phase);
        return id;
    }

    private void seedStock(String item, String batch, int qty) {
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, STATUS, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, ?, 'CCF 测试物料', '1', "
                + "'junit', NOW(), '0', 0)", "ccf-batch-1", batch, item);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'WH-CCF', ?, 'CCF 测试物料', ?, '', ?, ?, 'junit', NOW(), '0', 0)",
                "ccf-stock-1", item, batch, qty, qty);
    }

    private void seedUser(String id, String username, String nick) {
        jdbc.update("INSERT INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'x', ?, '1', 'junit', NOW(), '0', 0)",
                id, username, nick);
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, "x",
                java.util.Arrays.stream(roles)
                        .map(r -> new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + r))
                        .toList()));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_pm_cost_collect WHERE PROJECT_ID LIKE 'ccf-%' "
                + "OR SOURCE_ID LIKE 'ccf-%'");
        jdbc.update("DELETE FROM erp_pm_mat_issue_line WHERE ISSUE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_pm_mat_issue WHERE PROJECT_ID LIKE 'ccf-%') t)");
        jdbc.update("DELETE FROM erp_pm_mat_issue WHERE PROJECT_ID LIKE 'ccf-%'");
        jdbc.update("DELETE FROM erp_pm_sub_payment WHERE PROJECT_ID LIKE 'ccf-%'");
        jdbc.update("DELETE FROM erp_pm_subcontract WHERE PROJECT_ID LIKE 'ccf-%'");
        jdbc.update("DELETE FROM erp_pm_travel_claim WHERE PROJECT_ID LIKE 'ccf-%' OR EMP_ID='ccf-emp'");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST') t)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='PROJECT_COST'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE='CCF-ITEM-1'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE='CCF-ITEM-1'");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID LIKE 'ccf-%'");
        jdbc.update("DELETE FROM erp_pm_project WHERE ID LIKE 'ccf-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID='ccf-emp'");
    }
}
