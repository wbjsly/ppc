package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.bi.ExportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超量导出审批库测（add-export-console，spec bi-export-overlimit-approval）：
 * 拦截待审批 / 用途 ≥10 字 / 经理与总监分级 / 签署通过消费 / 驳回 FAILED /
 * 自签 403 / 月度频次告警 ROLE_DPO。
 */
@SpringBootTest
class ExportOverLimitDbTest {

    @Autowired
    private ExportService service;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SystemAuth.login("expov-designer", "ROLE_BI_DESIGNER");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='BiExportOverLimit'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='BiExportOverLimit'");
        // 注意：pattern 须覆盖 expov-designer（连字符）与 expov_freq_user（下划线）
        jdbc.update("DELETE FROM erp_bi_export_task WHERE USER_NAME LIKE 'expov%' "
                + "OR CREATE_BY LIKE 'expov%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN "
                + "('BiExportOverlimitFreq','BiExportRejected')");
    }

    private Map<String, Object> submitOver(long estRows, String purpose) {
        Map<String, Object> p = new HashMap<>();
        p.put("format", "csv");
        p.put("purpose", purpose);
        return service.submit("cost", p, estRows);
    }

    // ---------- 拦截与用途（spec 需求一） ----------

    @Test
    void overLimitEntersPending() {
        Map<String, Object> sub = submitOver(150000, "月度经营分析导出用途说明");
        assertEquals("PENDING", sub.get("approvalStatus"), "超量待审批");
        assertEquals(Boolean.TRUE, sub.get("overLimit"), "超量标志");
        assertNotNull(sub.get("approvalInstanceId"), "审批实例创建");
        assertEquals("ROLE_DEPT_DIRECTOR", sub.get("approverRole"), ">5万总监级");
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_export_task WHERE ID=?", String.class,
                (String) sub.get("id"));
        assertEquals("QUEUED", st, "待审批仍 QUEUED 但 worker 跳过");
    }

    @Test
    void purposeTooShortRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> submitOver(50000, "五个字用途"));
        assertEquals(422, e.getCode(), "用途 <10 字 422");
    }

    @Test
    void managerLevelApproval() {
        Map<String, Object> sub = submitOver(30000, "季度成本分析导出用途说明");
        assertEquals("ROLE_DEPT_MANAGER", sub.get("approverRole"), "≤5万经理级");
    }

    // ---------- 签署与驳回（spec 需求二） ----------

    @Test
    void signApprovedAllowsConsumption() {
        Map<String, Object> sub = submitOver(30000, "季度成本分析导出用途说明");
        String id = (String) sub.get("id");
        approve((String) sub.get("approvalInstanceId"), "expov-mgr");
        String ap = jdbc.queryForObject(
                "SELECT APPROVAL_STATUS FROM erp_bi_export_task WHERE ID=?", String.class, id);
        assertEquals("APPROVED", ap, "签署通过");
        // worker 现在可消费
        service.drainOnce();
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_export_task WHERE ID=?", String.class, id);
        assertTrue(List.of("DONE", "FAILED").contains(st), "审批后被消费（" + st + "）");
    }

    @Test
    void rejectedMarksFailedAndNotifies() {
        Map<String, Object> sub = submitOver(30000, "季度成本分析导出用途说明");
        String id = (String) sub.get("id");
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                        + "ON i.ID=t.APPR_ID WHERE i.BIZ_TYPE='BiExportOverLimit' "
                        + "AND i.BIZ_ID=? AND t.SEQ=1 AND t.NODE_TYPE='SIGN' LIMIT 1",
                String.class, id);
        SystemAuth.logout();
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "expov-mgr", null,
                        List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_DEPT_MANAGER"))));
        try {
            approvalEngine.reject(taskId, "用途不充分");
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("expov-designer", "ROLE_BI_DESIGNER");
        }
        String ap = jdbc.queryForObject(
                "SELECT APPROVAL_STATUS FROM erp_bi_export_task WHERE ID=?", String.class, id);
        assertEquals("REJECTED", ap, "驳回");
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_export_task WHERE ID=?", String.class, id);
        assertEquals("FAILED", st, "驳回后任务 FAILED");
        Integer notice = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='BiExportRejected' "
                        + "AND BIZ_ID=?", Integer.class, id);
        assertTrue(notice >= 1, "驳回通知");
    }

    // ---------- 频次告警（spec 需求三，C-4.10-03） ----------

    @Test
    void monthlyFrequencyAlertsDpo() {
        // 造 4 次历史已批准（DONE 不计并行上限）+ 本次第 5 次 → 告警
        String user = "expov_freq_user";
        for (int i = 0; i < 4; i++) {
            jdbc.update("INSERT INTO erp_bi_export_task (ID, TASK_TYPE, TASK_NO, USER_NAME, "
                            + "STATUS, APPROVAL_STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?, 'EXPORT', ?, ?, 'DONE', 'APPROVED', 'exp-test', "
                            + "DATE_ADD(NOW(), INTERVAL ? MINUTE), '0', 0)",
                    uuid(), "EXFREQ" + i, user, i);
        }
        SystemAuth.logout();
        SystemAuth.login(user, "ROLE_BI_DESIGNER");
        Map<String, Object> sub = submitOver(30000, "频次告警测试用途说明");
        approve((String) sub.get("approvalInstanceId"), "expov-mgr2");
        Integer alert = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='BiExportOverlimitFreq' "
                        + "AND BIZ_ID=?", Integer.class, user);
        assertTrue(alert >= 1, "月度 ≥5 次告警 ROLE_DPO");
    }

    // ---------- 自签禁令（C-0-03） ----------

    @Test
    void selfSignBlocked() {
        // 申请人 = 同时持有 ROLE_DEPT_MANAGER 的用户 → 其自签自己的申请须 403
        SystemAuth.logout();
        SystemAuth.login("expov-mgrself", "ROLE_BI_DESIGNER", "ROLE_DEPT_MANAGER");
        Map<String, Object> sub = submitOver(30000, "自签拦截测试用途说明");
        String taskId = jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                        + "ON i.ID=t.APPR_ID WHERE i.BIZ_TYPE='BiExportOverLimit' "
                        + "AND i.BIZ_ID=? AND t.SEQ=1 AND t.NODE_TYPE='SIGN' LIMIT 1",
                String.class, (String) sub.get("id"));
        // 同一人（申请发起人=expov-mgrself）尝试签署 → 自签 403
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> approvalEngine.pass(taskId, "自签尝试"));
            assertEquals(403, e.getCode(), "自签 403");
        } finally {
            SystemAuth.logout();
            SystemAuth.login("expov-designer", "ROLE_BI_DESIGNER");
        }
    }

    // ---------- helpers ----------

    /** 签署（signer ≠ 发起人） */
    private void approve(String instId, String signer) {
        String taskId = jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID=? AND SEQ=1 "
                        + "AND NODE_TYPE='SIGN' ORDER BY ID LIMIT 1",
                String.class, instId);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        signer, null,
                        List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_DEPT_MANAGER"))));
        try {
            approvalEngine.pass(taskId, "导出超量审批通过");
        } finally {
            SecurityContextHolder.clearContext();
            SystemAuth.login("expov-designer", "ROLE_BI_DESIGNER");
        }
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
