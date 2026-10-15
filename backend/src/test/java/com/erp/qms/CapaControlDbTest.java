package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.entity.qms.Capa;
import com.erp.entity.qms.Ncr;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.qms.CapaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6.6 根因分析（change rework-capa-evidence，spec capa-management）：
 * D7 三件套数据支撑、验证期复发自动回炉、D1 团队/D8 沉淀结构化、
 * 立项 5 工作日时限、page view 三叶过滤与聚合、待办反查幂等。
 */
@SpringBootTest
class CapaControlDbTest {

    @Autowired
    private CapaService capaService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asQualityEng();
        // D7 三件套用（VERIFYING，D7 PENDING，措施已全 DONE）
        insertCapa("capa-d7-1", "CAPA-D7-0001", "VERIFYING", "D7", "ncr-cap-1");
        insertDoneAction("capa-d7-1", "act-d7-1");
        // 复发回炉用（VERIFYING，绑定 ncr-cap-2 物料 REC-ITEM-01）
        insertCapa("capa-rec-1", "CAPA-REC-0001", "VERIFYING", "D7", "ncr-cap-2");
        insertDoneAction("capa-rec-1", "act-rec-1");
        insertNcr("ncr-cap-2", "NCR-CAP-0002", "REC-ITEM-01", "MAJOR");
        // D1 清单用（OPEN，D1 PENDING）
        insertCapa("capa-d1-1", "CAPA-D1-0001", "OPEN", "D1", null);
        // D8 沉淀用（VERIFYING，D8 PENDING，verifyResult=VALID，措施全 DONE）
        insertCapa("capa-d8-1", "CAPA-D8-0001", "VERIFYING", "D8", null);
        insertDoneAction("capa-d8-1", "act-d8-1");
        jdbc.update("UPDATE erp_qms_capa SET VERIFY_RESULT='VALID', VERIFY_INDICATOR='不合格率', "
                + "VERIFY_TARGET='≤0.5%', VERIFY_DATA='措施前: 5% | 措施后: 0.4% | 周期: 3个月' WHERE ID='capa-d8-1'");
        // dueDate 用 NCR（评审时间 2026-10-12 周一）
        insertNcr("ncr-due-1", "NCR-DUE-0001", "DUE-ITEM-01", "MAJOR");
        jdbc.update("UPDATE erp_qms_ncr SET REVIEW_TIME='2026-10-12 10:00:00' WHERE ID='ncr-due-1'");
        // 待办反查用
        insertNcr("ncr-byn-1", "NCR-BYN-0001", "BYN-ITEM-01", "MINOR");
        // view 聚合用：带未完成措施的在途 CAPA + 无措施的
        insertCapa("capa-vw-1", "CAPA-VW-0001", "ANALYZING", "D5", null);
        jdbc.update("INSERT INTO erp_qms_capa_action (ID, CAPA_ID, ACTION_TYPE, DESCRIPTION, OWNER_NAME, "
                + "DUE_DATE, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('act-vw-1a', 'capa-vw-1', 'CORRECT', '措施A', '张三', '2099-01-01', 'PENDING', 'junit', '0', 0), "
                + "('act-vw-1b', 'capa-vw-1', 'PREVENT', '措施B', '李四', '2099-01-01', 'DONE', 'junit', '0', 0)");
        insertCapa("capa-vw-2", "CAPA-VW-0002", "ANALYZING", "D4", null);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('QMS_CAPA_REANALYZE', 'QMS_CAPA_RECUR')");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON a.ID=t.APPR_ID "
                + "WHERE a.BIZ_TYPE='CapaStandardChange' AND a.TITLE LIKE '%CAPA-%'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='CapaStandardChange' AND TITLE LIKE '%CAPA-%'");
        jdbc.update("DELETE FROM erp_qms_capa_action WHERE CAPA_ID LIKE 'capa-%'");
        jdbc.update("DELETE FROM erp_qms_capa_step WHERE CAPA_ID LIKE 'capa-%'");
        jdbc.update("DELETE FROM erp_qms_capa WHERE ID LIKE 'capa-%'");
        jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID LIKE 'ncr-cap-%' OR NCR_ID LIKE 'ncr-due-%' "
                + "OR NCR_ID LIKE 'ncr-byn-%'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID LIKE 'ncr-cap-%' OR ID LIKE 'ncr-due-%' OR ID LIKE 'ncr-byn-%'");
    }

    private void insertCapa(String id, String no, String status, String step, String ncrId) {
        jdbc.update("INSERT INTO erp_qms_capa (ID, CAPA_NO, NCR_ID, SOURCE_TYPE, TITLE, PROBLEM_DESC, "
                + "SEVERITY, OWNER_NAME, CURRENT_STEP, STATUS, VERIFY_RESULT, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, 'MANUAL', '测试 CAPA', '问题描述', 'MAJOR', '质量工程师', ?, ?, 'PENDING', 'junit', '0', 0)",
                id, no, ncrId, step, status);
        String[] codes = {"D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8"};
        for (String c : codes) {
            jdbc.update("INSERT INTO erp_qms_capa_step (ID, CAPA_ID, STEP_CODE, STEP_NAME, STATUS, "
                    + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, ?, 'junit', '0', 0)",
                    id + "-" + c, id, c, c, c.equals(step) ? "PENDING" : (isDoneBefore(c, step) ? "DONE" : "PENDING"));
        }
    }

    private boolean isDoneBefore(String code, String current) {
        return List.of("D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8").indexOf(code)
                < List.of("D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8").indexOf(current);
    }

    private void insertDoneAction(String capaId, String actionId) {
        jdbc.update("INSERT INTO erp_qms_capa_action (ID, CAPA_ID, ACTION_TYPE, DESCRIPTION, OWNER_NAME, "
                + "DUE_DATE, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'CORRECT', '已完成措施', "
                + "'张三', '2026-01-01', 'DONE', 'junit', '0', 0)", actionId, capaId);
    }

    private void insertNcr(String id, String no, String item, String severity) {
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, DEFECT_ITEM, QTY, SEVERITY, "
                + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, '测试物料', '外观', 10, ?, 'CREATED', "
                + "'junit', '0', 0)", id, no, item, severity);
    }

    private void asQualityEng() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_QUALITY_ENG"));
    }

    private void asQualityMgr() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_QUALITY_MGR"));
    }

    // ================= D7 三件套 =================

    @Test
    void d7RequiresIndicator() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                capaService.advanceStep("capa-d7-1", Map.of(
                        "stepCode", "D7", "ownerName", "王五", "output", "验证报告",
                        "verifyTarget", "≤0.5%", "verifyBefore", "5%", "verifyAfter", "0.4%",
                        "verifyPeriod", "3个月", "verifyResult", "VALID")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("验证指标"), "缺指标提示：" + e.getMessage());
    }

    @Test
    void d7CompletesWithThreeParts() {
        Map<String, Object> res = capaService.advanceStep("capa-d7-1", Map.of(
                "stepCode", "D7", "ownerName", "王五", "output", "验证报告",
                "verifyIndicator", "批次不合格率", "verifyTarget", "≤0.5%",
                "verifyBefore", "5.0%", "verifyAfter", "0.4%", "verifyPeriod", "3个月",
                "verifyResult", "VALID"));
        Capa c = (Capa) res.get("capa");
        assertEquals("VALID", c.getVerifyResult());
        assertEquals("批次不合格率", c.getVerifyIndicator());
        assertTrue(c.getVerifyData().contains("5.0%") && c.getVerifyData().contains("3个月"),
                "跟踪数据规范文本：" + c.getVerifyData());
        assertEquals("D8", jdbc.queryForObject(
                "SELECT CURRENT_STEP FROM erp_qms_capa WHERE ID='capa-d7-1'", String.class));
    }

    // ================= 验证期复发自动回炉 =================

    @Test
    void recurrenceInvalidatesVerifyingCapa() {
        Ncr newNcr = jdbc.queryForObject("SELECT * FROM erp_qms_ncr WHERE ID='ncr-cap-2'",
                (rs, i) -> {
                    Ncr n = new Ncr();
                    n.setId("ncr-cap-2");
                    n.setNcrNo("NCR-CAP-0002");
                    n.setItemCode("REC-ITEM-01");
                    return n;
                });
        int fired = capaService.checkVerificationRecurrence(newNcr);
        assertEquals(1, fired, "同物料 VERIFYING CAPA 命中回炉");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT STATUS, VERIFY_RESULT, CURRENT_STEP, REMARK FROM erp_qms_capa WHERE ID='capa-rec-1'");
        assertEquals("REANALYZING", row.get("STATUS"));
        assertEquals("INVALID", row.get("VERIFY_RESULT"));
        assertEquals("D4", row.get("CURRENT_STEP"));
        assertTrue(String.valueOf(row.get("REMARK")).contains("NCR-CAP-0002"), "留痕新 NCR 单号");
        Long todo = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='QMS_CAPA_RECUR' AND BIZ_ID='capa-rec-1'",
                Long.class);
        assertEquals(1L, todo, "待办生成");
        // D4 起步骤重置
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_capa_step WHERE CAPA_ID='capa-rec-1' AND STATUS='PENDING'",
                Long.class);
        assertTrue(pending >= 5, "D4 起步骤重置为 PENDING（实际 " + pending + "）");
    }

    @Test
    void recurrenceIgnoresDifferentItem() {
        Ncr other = jdbc.queryForObject("SELECT * FROM erp_qms_ncr WHERE ID='ncr-due-1'",
                (rs, i) -> {
                    Ncr n = new Ncr();
                    n.setId("ncr-due-1");
                    n.setNcrNo("NCR-DUE-0001");
                    n.setItemCode("DUE-ITEM-01");
                    return n;
                });
        int fired = capaService.checkVerificationRecurrence(other);
        assertEquals(0, fired, "不同物料不误伤");
        assertEquals("VERIFYING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_capa WHERE ID='capa-rec-1'", String.class));
    }

    // ================= D1 团队清单 =================

    @Test
    void d1RejectsEmptyMembers() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                capaService.advanceStep("capa-d1-1", Map.of(
                        "stepCode", "D1", "ownerName", "张三", "output", "启动会议")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("成员"), "空清单提示：" + e.getMessage());
    }

    @Test
    void d1SingleQualityMemberWarns() {
        Map<String, Object> res = capaService.advanceStep("capa-d1-1", Map.of(
                "stepCode", "D1", "ownerName", "张三", "output", "启动会议",
                "teamMembers", "[{\"name\":\"张三\",\"domain\":\"质量\",\"duty\":\"Leader 统筹\",\"leader\":true}]"));
        assertNotNull(res.get("warnings"), "单人全质量领域软警示");
        assertEquals("ANALYZING", ((Capa) res.get("capa")).getStatus());
    }

    @Test
    void d1MultiDomainPasses() {
        Map<String, Object> res = capaService.advanceStep("capa-d1-1", Map.of(
                "stepCode", "D1", "ownerName", "张三", "output", "启动会议",
                "teamMembers", "[{\"name\":\"张三\",\"domain\":\"质量\",\"duty\":\"Leader\",\"leader\":true},"
                        + "{\"name\":\"李四\",\"domain\":\"生产\",\"duty\":\"工艺排查\",\"leader\":false}]"));
        assertNull(res.get("warnings"), "多领域无警示");
    }

    // ================= D8 沉淀 =================

    @Test
    void d8RejectsEmptySink() {
        ServiceException e = assertThrows(ServiceException.class, () ->
                capaService.advanceStep("capa-d8-1", Map.of(
                        "stepCode", "D8", "ownerName", "张三", "output", "关闭总结")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("沉淀"), "全未勾提示：" + e.getMessage());
    }

    @Test
    void d8StandardSinkRequiresApproval() {
        // 首次提交：发起标准变更审批并挂起（pendingApproval，D8 未完成）
        Map<String, Object> res = capaService.advanceStep("capa-d8-1", Map.of(
                "stepCode", "D8", "ownerName", "张三", "output", "关闭总结",
                "legacySink", "[{\"type\":\"STANDARD\",\"note\":\"修订检验标准 QP-01 增加 CTQ 项\"}]"));
        String approvalId = (String) res.get("pendingApproval");
        assertNotNull(approvalId, "首次提交发起审批");
        assertEquals("VERIFYING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_capa WHERE ID='capa-d8-1'", String.class), "审批未过 D8 未完成");
        // 未通过再次提交 → 422
        ServiceException e = assertThrows(ServiceException.class, () ->
                capaService.advanceStep("capa-d8-1", Map.of(
                        "stepCode", "D8", "ownerName", "张三", "output", "关闭总结",
                        "legacySink", "[{\"type\":\"STANDARD\",\"note\":\"修订检验标准 QP-01 增加 CTQ 项\"}]",
                        "sinkApprovalId", approvalId)));
        assertEquals(422, e.getCode());
        // 经理审批通过 → 再次提交完成 D8
        asQualityMgr();
        String taskId = approvalEngine.todo().stream()
                .filter(t -> "CapaStandardChange".equals(t.get("bizType"))
                        && "capa-d8-1".equals(t.get("bizId")))
                .map(t -> String.valueOf(t.get("taskId")))
                .findFirst().orElse(null);
        assertNotNull(taskId, "审批待办存在");
        approvalEngine.pass(taskId, "同意标准修订");
        asQualityEng();
        Map<String, Object> res2 = capaService.advanceStep("capa-d8-1", Map.of(
                "stepCode", "D8", "ownerName", "张三", "output", "关闭总结",
                "legacySink", "[{\"type\":\"STANDARD\",\"note\":\"修订检验标准 QP-01 增加 CTQ 项\"}]",
                "sinkApprovalId", approvalId));
        assertEquals("CLOSED", ((Capa) res2.get("capa")).getStatus(), "审批过后 D8 完成关闭");
    }

    @Test
    void d8NonStandardSinkClosesDirectly() {
        Map<String, Object> res = capaService.advanceStep("capa-d8-1", Map.of(
                "stepCode", "D8", "ownerName", "张三", "output", "关闭总结",
                "legacySink", "[{\"type\":\"TRAINING\",\"note\":\"更新培训教材 TR-07\"},"
                        + "{\"type\":\"POKA_YOKE\",\"note\":\"防错清单 PK-03 加装防错销\"}]"));
        assertNull(res.get("pendingApproval"), "无标准修订不挂审批");
        assertEquals("CLOSED", ((Capa) res.get("capa")).getStatus());
    }

    // ================= 立项时限 =================

    @Test
    void manualCreateComputesWorkdayDue() {
        Capa c = capaService.createManual(Map.of(
                "title", "测试立项（周一评审 +5 工作日）", "severity", "MAJOR",
                "sourceType", "MANUAL", "ncrId", "ncr-due-1"));
        assertEquals(java.time.LocalDate.of(2026, 10, 19), c.getDueDate(),
                "周一 10-12 评审 → 下周一 10-19（跨周末顺延）");
    }

    @Test
    void complaintCreateHasNoDue() {
        Capa c = capaService.createManual(Map.of(
                "title", "投诉立项（独立来源无时限）", "severity", "MINOR", "sourceType", "COMPLAINT"));
        assertNull(c.getDueDate(), "独立来源不计时限");
    }

    // ================= page view 与待办反查 =================

    @Test
    void actionsViewFiltersAndAggregates() {
        var page = capaService.page(1, 50, null, null, "actions");
        List<Map<String, Object>> ids = page.getRecords();
        assertTrue(ids.stream().anyMatch(m -> "capa-vw-1".equals(m.get("id"))), "含未完成措施的在途单在列");
        assertTrue(ids.stream().noneMatch(m -> "capa-vw-2".equals(m.get("id"))), "无措施的在途单不在列");
        Map<String, Object> vw1 = ids.stream().filter(m -> "capa-vw-1".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals(1, vw1.get("actionsDone"), "完成数聚合");
        assertEquals(2, vw1.get("actionsTotal"), "总数聚合");
        assertEquals(false, vw1.get("actionOverdue"), "未超期");
    }

    @Test
    void verifyViewIncludesReanalyzing() {
        insertCapa("capa-vw-3", "CAPA-VW-0003", "REANALYZING", "D4", null);
        var page = capaService.page(1, 50, null, null, "verify");
        List<Map<String, Object>> ids = page.getRecords();
        assertTrue(ids.stream().anyMatch(m -> "capa-vw-3".equals(m.get("id"))), "回炉单在验证叶可见");
        assertTrue(ids.stream().noneMatch(m -> "capa-vw-1".equals(m.get("id"))), "分析中单不在验证叶");
    }

    @Test
    void defaultViewUnchanged() {
        var page = capaService.page(1, 50, null, null, null);
        assertTrue(page.getTotal() >= 6, "无 view 参数回归全量（实际 " + page.getTotal() + "）");
        assertTrue(page.getRecords().stream().noneMatch(m -> m.containsKey("actionsTotal")),
                "默认视图不带聚合字段");
    }

    @Test
    void byNcrLookupIdempotent() {
        Capa c = capaService.findByNcr("ncr-cap-2");
        assertNotNull(c, "按 NCR 反查返回既有 CAPA");
        assertEquals("capa-rec-1", c.getId());
        assertNull(capaService.findByNcr("ncr-byn-1"), "无 CAPA 的 NCR 返回 null（待办走立项预填）");
    }
}
