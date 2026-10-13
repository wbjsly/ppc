package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.vmi.MaterialIssue;
import com.erp.entity.vmi.MaterialIssueLine;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.vmi.MaterialIssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试产备料三挂点库测（change add-trial-production-management，spec trial-execution
 * 「试产备料与批次打标」FR-4.5-8-4，任务 3.4）：
 * ① 样品批次优先排前（TRIAL_FLAG=1 先于 FIFO 量产批次）；
 * ② 挪用量产料双人确认（全 TRIAL_FLAG=0 过账 422 → TrialBorrow 计划发起/工艺主管签署 → 过账成功）；
 * ③ 过账同事务批次打标 TRIAL_FLAG=1；含样品批次免审批直过。
 */
@SpringBootTest
class TrialBorrowDbTest {

    @Autowired
    private MaterialIssueService issueService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("borrow-tester", "ROLE_WAREHOUSE", "ROLE_PLANNER", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                + "WHERE a.BIZ_TYPE='TrialBorrow'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='TrialBorrow'");
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID IN "
                + "(SELECT ID FROM erp_inv_material_issue WHERE CREATE_BY='borrow-tester')");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE CREATE_BY='borrow-tester'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '%试产备料%')");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '%试产备料%')");
        MoSeed.cleanup(jdbc);
    }

    // ---------- 助手 ----------

    private MdmItem mkItem(String name) {
        return MoSeed.mkItem(itemService, categoryCode, name, "BUY");
    }

    /** 库存行（WH-MAIN + 批次；createDate 显式错开控 FIFO 序） */
    private void mkStock(String itemCode, String batch, String qty, String createDate) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                        + "BATCH_NO, QTY, AVAILABLE_QTY, CREATE_BY, CREATE_DATE) VALUES "
                        + "(?,?,?,?,?,?,?, 'junit', ?)",
                "st-trb-" + itemCode + "-" + batch, "WH-MAIN", itemCode, "试产备料物料",
                batch, new BigDecimal(qty), new BigDecimal(qty), createDate);
    }

    /** 批次台账行（TRIAL_FLAG / 效期锁定缺省） */
    private void mkBatch(String itemCode, String batch, String trialFlag) {
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME,"
                        + " TRIAL_FLAG, STATUS, CREATE_BY) VALUES (?,?,?,?,?,?, 'junit')",
                "bt-trb-" + itemCode + "-" + batch, batch, itemCode, "试产备料物料",
                trialFlag, "1");
    }

    private String trialFlag(String itemCode, String batch) {
        return jdbc.queryForObject("SELECT TRIAL_FLAG FROM erp_inv_batch "
                + "WHERE ITEM_CODE=? AND BATCH_NO=?", String.class, itemCode, batch);
    }

    private Map<String, Object> createIssue(String itemCode, String workOrderNo, String qty) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("issueType", "OWN");
        payload.put("workOrderNo", workOrderNo);
        payload.put("lines", List.of(Map.of("itemCode", itemCode, "qty", new BigDecimal(qty))));
        return issueService.create(payload);
    }

    private String issueId(Map<String, Object> created) {
        return ((MaterialIssue) created.get("issue")).getId();
    }

    @SuppressWarnings("unchecked")
    private List<MaterialIssueLine> linesOf(Map<String, Object> created) {
        return (List<MaterialIssueLine>) created.get("lines");
    }

    private String activeBorrowTaskId(String issueNo) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID "
                        + "WHERE a.BIZ_TYPE='TrialBorrow' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, issueNo);
    }

    // ================= ① 样品批次优先 =================

    @Test
    void sampleBatchAllocatedFirst() {
        MdmItem item = mkItem("试产备料优先产品");
        // 量产批次更早入账（纯 FIFO 会先选它）→ 试产专用标记应使其让位
        mkStock(item.getItemCode(), "MASS-B1", "20", "2026-01-01 08:00:00");
        mkStock(item.getItemCode(), "TRIAL-B1", "10", "2026-06-01 08:00:00");
        mkBatch(item.getItemCode(), "MASS-B1", "0");
        mkBatch(item.getItemCode(), "TRIAL-B1", "1");

        Map<String, Object> created = createIssue(item.getItemCode(), "TP-20261012-001", "5");
        List<MaterialIssueLine> lines = linesOf(created);
        assertEquals(1, lines.size(), "试产专用批次足量 → 单行配批");
        assertEquals("TRIAL-B1", lines.get(0).getBatchNo(),
                "TRIAL_FLAG=1 样品批次优先于更早的量产批次（spec 场景「样品批次优先分配」）");
    }

    // ================= ②③ 挪用双人确认 + 过账打标 =================

    @Test
    void massBorrowRequiresDualApprovalThenMarksBatches() {
        MdmItem item = mkItem("试产备料挪用产品");
        mkStock(item.getItemCode(), "MASS-B2", "20", "2026-01-01 08:00:00");
        mkBatch(item.getItemCode(), "MASS-B2", "0");

        Map<String, Object> created = createIssue(item.getItemCode(), "TP-20261012-002", "6");
        String id = issueId(created);
        String issueNo = ((MaterialIssue) created.get("issue")).getIssueNo();

        // 未审批 → 过账 422 阻断（spec 场景「挪用量产批次阻断」）
        var blocked = assertThrows(ServiceException.class, () -> issueService.post(id));
        assertEquals(422, blocked.getCode(), "挪用未确认 422：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("双人确认"), blocked.getMessage());
        assertTrue(blocked.getMessage().contains("TrialBorrow"), blocked.getMessage());
        assertEquals("0", trialFlag(item.getItemCode(), "MASS-B2"), "422 后批次未打标");

        // 计划员发起（applyBorrow 限 PLANNER 侧）
        Map<String, Object> apply = issueService.applyBorrow(id);
        assertEquals(Boolean.TRUE, apply.get("pending"), "发起成功：" + apply);
        // 幂等：重复发起复用同实例
        Map<String, Object> again = issueService.applyBorrow(id);
        assertEquals(apply.get("apprNo"), again.get("apprNo"), "PENDING 幂等同一实例");

        // 工艺主管签署（发起 borrow-tester ≠ 签署 proc-borrow，C-0-03）
        String taskId = activeBorrowTaskId(issueNo);
        MoTestAuth.login("proc-borrow", "ROLE_PROCESS_MGR");
        approvalEngine.pass(taskId, "同意挪用");

        // 过账成功 + 同事务打标
        MoTestAuth.login("borrow-tester", "ROLE_WAREHOUSE", "ROLE_PLANNER", "ROLE_ADMIN");
        Map<String, Object> posted = issueService.post(id);
        assertEquals("POSTED", ((MaterialIssue) posted.get("issue")).getStatus(), "审批后过账成功");
        assertEquals("1", trialFlag(item.getItemCode(), "MASS-B2"),
                "过账同事务批次打标 TRIAL_FLAG=1（spec 场景「过账成功置 1」）");
        // 库存实扣
        BigDecimal avail = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO=?",
                BigDecimal.class, item.getItemCode(), "MASS-B2");
        assertEquals(0, avail.compareTo(new BigDecimal("14")), "实扣 6 后可用 14");
    }

    // ================= 含样品批次免审批直过 =================

    @Test
    void issueWithSampleBatchSkipsBorrowApproval() {
        MdmItem item = mkItem("试产备料免审批产品");
        // 样品批次 5 + 量产批次 10：需 8 → 混合配批（5 样品 + 3 量产）
        mkStock(item.getItemCode(), "TRIAL-B3", "5", "2026-06-01 08:00:00");
        mkStock(item.getItemCode(), "MASS-B3", "10", "2026-01-01 08:00:00");
        mkBatch(item.getItemCode(), "TRIAL-B3", "1");
        mkBatch(item.getItemCode(), "MASS-B3", "0");

        Map<String, Object> created = createIssue(item.getItemCode(), "TP-20261012-003", "8");
        List<MaterialIssueLine> lines = linesOf(created);
        assertEquals(2, lines.size(), "样品不足 → 混合配批两行");
        assertEquals("TRIAL-B3", lines.get(0).getBatchNo(), "样品批次行在前");
        assertEquals(0, lines.get(0).getQty().compareTo(new BigDecimal("5")), "样品批次足量取满 5");
        assertEquals("MASS-B3", lines.get(1).getBatchNo(), "缺口由量产批次补齐 3");

        // 含 TRIAL_FLAG=1 批次 → 免审批直过（spec 场景「样品批次领料免审批」）
        String id = issueId(created);
        Map<String, Object> posted = issueService.post(id);
        assertEquals("POSTED", ((MaterialIssue) posted.get("issue")).getStatus(), "免审批过账成功");
        Integer borrowInstances = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval WHERE BIZ_TYPE='TrialBorrow'",
                Integer.class);
        assertEquals(0, borrowInstances, "未产生 TrialBorrow 实例");
    }

    // ================= 挪用确认权限面 =================

    @Test
    void borrowApprovalGateRoles() {
        MdmItem item = mkItem("试产备料权限产品");
        mkStock(item.getItemCode(), "MASS-B4", "10", "2026-01-01 08:00:00");
        mkBatch(item.getItemCode(), "MASS-B4", "0");
        Map<String, Object> created = createIssue(item.getItemCode(), "TP-20261012-004", "3");
        String id = issueId(created);

        // 非计划侧角色发起 → 403
        MoTestAuth.login("wh-only", "ROLE_WAREHOUSE");
        var forbidden = assertThrows(ServiceException.class, () -> issueService.applyBorrow(id));
        assertEquals(403, forbidden.getCode(), "非计划侧 403：" + forbidden.getMessage());

        // 非试产工单领料单不可发起
        MoTestAuth.login("borrow-tester", "ROLE_WAREHOUSE", "ROLE_PLANNER", "ROLE_ADMIN");
        Map<String, Object> normalIssue = createIssue(item.getItemCode(), "MO-20261012-001", "2");
        String normalId = issueId(normalIssue);
        var notTrial = assertThrows(ServiceException.class,
                () -> issueService.applyBorrow(normalId));
        assertEquals(422, notTrial.getCode(), "非试产单 422：" + notTrial.getMessage());
        assertTrue(notTrial.getMessage().contains("TP-"), notTrial.getMessage());

        // 正常工单领料（全量产批次）不触发挪用闸门——直接过账（闸门仅试产单）
        Map<String, Object> posted = issueService.post(normalId);
        assertEquals("POSTED", ((MaterialIssue) posted.get("issue")).getStatus(),
                "正常工单领料免试产闸门");
    }
}
