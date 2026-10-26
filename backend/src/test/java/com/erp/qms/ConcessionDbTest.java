package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.entity.qms.Concession;
import com.erp.service.qms.ConcessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 让步接收全链（spec concession-acceptance，change rework-qualified-release task 4.1 补测试）：
 * 申请要素 422、安全/法规 CTQ 禁用、双签批准、核销成功扣额度、越界 422 留 REJECTED 记录。
 */
@SpringBootTest
class ConcessionDbTest {

    @Autowired
    private ConcessionService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.erp.service.approval.ApprovalEngine approvalEngine;

    @BeforeEach
    void seed() {
        purge();
        asRole("ROLE_QUALITY_ENG");
        // 普通 NCR（已评审为让步）+ 未入库 GR 行
        insertNcr("ncr-cc-1", "NCR-CC-0001", "0");
        // 安全/法规 NCR
        insertNcr("ncr-cc-2", "NCR-CC-0002", "1");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        // 审批残留会干扰后续 submit/签署 → 按 BIZ_TYPE + 标题锚（title 含 CC-ITEM）全清
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a ON a.ID=t.APPR_ID "
                + "WHERE a.BIZ_TYPE='Concession' AND a.TITLE LIKE '%CC-ITEM%'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='Concession' AND TITLE LIKE '%CC-ITEM%'");
        jdbc.update("DELETE FROM erp_qms_concession_writeoff WHERE CONCESSION_NO LIKE 'CS-%' "
                + "OR CONCESSION_ID IN (SELECT ID FROM (SELECT ID FROM erp_qms_concession) t2)");
        jdbc.update("DELETE FROM erp_qms_concession WHERE NCR_ID LIKE 'ncr-cc-%' OR ITEM_CODE='CC-ITEM'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID LIKE 'ncr-cc-%' OR ITEM_CODE='CC-ITEM'");
        jdbc.update("DELETE FROM erp_proc_gr_line WHERE ID IN ('grl-cc-1','grl-cc-2')");
    }

    private void insertNcr(String id, String no, String regulatory) {
        jdbc.update("INSERT IGNORE INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT, "
                + "RECEIVED_QTY, STATUS, QC_STATUS, CREATE_BY, VER_NO) VALUES "
                + "(?, 'gr-cc-x', 1, 'CC-ITEM', '让步测试物料', 'PC', 100, 'REGISTERED', 'FROZEN', 'junit', 0)",
                id.equals("ncr-cc-1") ? "grl-cc-1" : "grl-cc-2");
        jdbc.update("INSERT IGNORE INTO erp_qms_ncr (ID, NCR_NO, LOT_ID, ITEM_CODE, ITEM_NAME, QTY, STATUS, "
                + "DISPOSITION, SEVERITY, REGULATORY_FLAG, CTQ_FLAG, BATCH_NO, GR_LINE_ID, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, 'CC-ITEM', '让步测试物料', 100, "
                + "'CONCESSION', 'CONCESSION', 'MAJOR', ?, '0', 'CC-BATCH', ?, 'junit', '0', 0)",
                id, no, id, regulatory, id.equals("ncr-cc-1") ? "grl-cc-1" : "grl-cc-2");
    }

    private void asRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, role));
    }

    private Map<String, Object> validBody(String ncrId) {
        return Map.of(
                "ncrId", ncrId,
                "reason", "供应商来料外观批量划伤，产线急用",
                "techAssess", "划伤非功能面，不影响装配与电气性能",
                "riskAssess", "低风险，外观让步，客户未指定外观 A 面",
                "limitUntil", LocalDate.now().plusDays(30).toString(),
                "limitQty", "80",
                "limitScope", "CC-BATCH");
    }

    private String signAll(String approvalId, String... roles) {
        String last = null;
        for (String role : roles) {
            String task = jdbc.queryForObject(
                    "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND STATUS='ACTIVE' "
                            + "AND ROLE_REQUIRED=? LIMIT 1", String.class, approvalId, role);
            asRole(role);
            approvalEngine.pass(task, "测试签署通过");
            last = task;
        }
        return last;
    }

    // ---------- 申请要素 422 逐条 ----------

    @Test
    void createMissingElements422() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.create(Map.of("ncrId", "ncr-cc-1")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("让步原因"), e.getMessage());
        assertTrue(e.getMessage().contains("技术评估"), e.getMessage());
        assertTrue(e.getMessage().contains("限制条件"), e.getMessage());
        // 无关联 NCR → 422
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.create(Map.of("reason", "x")));
        assertTrue(e2.getMessage().contains("NCR"), e2.getMessage());
    }

    // ---------- 安全/法规 CTQ 禁用（BL-4.12-05） ----------

    @Test
    void regulatoryCtqForbidden() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.create(validBody("ncr-cc-2")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("安全/法规"), e.getMessage());
    }

    // ---------- 双签批准（C-4.12-01） ----------

    @Test
    void doubleSignApproves() {
        Concession c = service.create(validBody("ncr-cc-1"));
        assertEquals("DRAFT", c.getStatus());
        service.submit(c.getId());
        assertEquals("PENDING_APPROVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_concession WHERE ID=?", String.class, c.getId()));
        String apprId = jdbc.queryForObject(
                "SELECT APPROVAL_ID FROM erp_qms_concession WHERE ID=?", String.class, c.getId());

        // 第 1 签（质量经理）
        signAll(apprId, "ROLE_QUALITY_MGR");
        assertEquals("PENDING_APPROVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_concession WHERE ID=?", String.class, c.getId()),
                "单签不生效（双签缺一不生效）");
        // 第 2 签（技术负责人）→ APPROVED
        signAll(apprId, "ROLE_TECH_OWNER");
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_concession WHERE ID=?", String.class, c.getId()),
                "双签全过批准");
        // GR 行让步标记
        assertEquals("CONCESSION", jdbc.queryForObject(
                "SELECT QC_STATUS FROM erp_proc_gr_line WHERE ID='grl-cc-1'", String.class));
    }

    // ---------- 核销扣额度 + 越界 422 留 REJECTED ----------

    @Test
    void writeOffSuccessAndOverLimitRejected() {
        Concession c = service.create(validBody("ncr-cc-1"));
        service.submit(c.getId());
        String apprId = jdbc.queryForObject(
                "SELECT APPROVAL_ID FROM erp_qms_concession WHERE ID=?", String.class, c.getId());
        signAll(apprId, "ROLE_QUALITY_MGR");
        signAll(apprId, "ROLE_TECH_OWNER");

        asRole("ROLE_INSPECTOR");
        // 成功核销 30（上限 80，范围含 CC-BATCH）
        Map<String, Object> ok = service.writeOff(Map.of(
                "concessionId", c.getId(), "qty", "30", "scope", "CC-BATCH 内部领用"));
        assertNotNull(ok.get("writeoff"), "核销留痕");
        BigDecimal remain = new BigDecimal(String.valueOf(ok.get("remainQty")));
        assertEquals(0, remain.compareTo(new BigDecimal("50")), "剩余额度 80-30=50：" + remain);

        // 越界：本次 60 > 剩余 50 → 422 + REJECTED 记录
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.writeOff(Map.of(
                        "concessionId", c.getId(), "qty", "60", "scope", "CC-BATCH 内部领用")));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("超出剩余额度"), e.getMessage());
        Integer rejected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_concession_writeoff WHERE CONCESSION_ID=? "
                        + "AND STATUS='REJECTED'", Integer.class, c.getId());
        assertEquals(1, rejected, "越界尝试留 REJECTED 记录");

        // 使用范围不符 → 422
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.writeOff(Map.of(
                        "concessionId", c.getId(), "qty", "10", "scope", "其他批次")));
        assertTrue(e2.getMessage().contains("使用范围"), e2.getMessage());

    }

    // ---------- 作废仅 DRAFT（APPROVED 不可作废） ----------

    @Test
    void cancelOnlyDraft() {
        Concession c = service.create(validBody("ncr-cc-1"));
        service.cancel(c.getId(), "计划有变");
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_concession WHERE ID=?", String.class, c.getId()));
        // 审批中不可作废：另造并提交
        Concession c2 = service.create(validBody("ncr-cc-1"));
        service.submit(c2.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.cancel(c2.getId(), "x"));
        assertEquals(422, e.getCode());
    }
}
