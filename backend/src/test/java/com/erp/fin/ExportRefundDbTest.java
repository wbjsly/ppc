package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.fin.ExportTaxRefundService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 出口退税 DbTest（add-tax-filing：spec export-tax-refund 5 需求 14 场景）。
 * 共享库隔离：整域清理 + junit 造数；退税率走 SysParam EXPORT_REFUND_RATE_DEFAULT。
 */
@SpringBootTest
class ExportRefundDbTest {

    @Autowired
    private ExportTaxRefundService service;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        // 退税率默认参数保底（missingRefundRateBlocks 会临时置空，各测试独立保底）
        sysParamService.setValue("EXPORT_REFUND_RATE_DEFAULT", "0.13", "NUMBER", "TAX",
                "测试保底", "junit");
        loginRole("ROLE_FINANCE");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE = 'EXPREFUND'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'EXPREFUND'");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                + "WHERE i.BIZ_TYPE IN ('ExportRefundApply')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('ExportRefundApply')");
        jdbc.update("DELETE FROM erp_fin_export_refund_apply WHERE APPLY_NO LIKE 'RFA%'");
        jdbc.update("DELETE FROM erp_fin_export_deal WHERE DEAL_NO LIKE 'EXP%'");
        jdbc.update("DELETE FROM erp_fin_tax_return WHERE ID = 'smkexp-vat'");
    }

    private static void loginRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, role));
    }

    /** 造一票一档基础 payload（FOB USD 100,000 汇率 7.1 → 710,000 CNY，退税率 13%） */
    private Map<String, Object> dealPayload(String suffix, String term, String gross,
                                            String freight, String insurance) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("invoiceNo", "EXP-INV-" + suffix);
        p.put("declNo", "EXP-DECL-" + suffix);
        p.put("declDate", LocalDate.now().minusMonths(2).toString());
        p.put("tradeTerm", term);
        p.put("currency", "USD");
        p.put("grossAmount", gross);
        p.put("freight", freight);
        p.put("insurance", insurance);
        p.put("commission", "0");
        p.put("fxRate", "7.1");
        p.put("itemCategory", "MACH");
        return p;
    }

    private String activeTaskId(String bizId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                        + "WHERE i.BIZ_ID = ? AND t.STATUS = 'ACTIVE' LIMIT 1", String.class, bizId);
    }

    // ================================================================
    // 5.1 FOB 折算与退税率（BR-4.14-41 / C-4.14-01）
    // ================================================================

    @Test
    void fobDirectCalculation() {
        Map<String, Object> out = service.registerDeal(dealPayload("fob", "FOB", "100000", "0", "0"));
        assertEquals(0, new BigDecimal("710000.00").compareTo((BigDecimal) out.get("fobCny")));
        assertEquals(0, new BigDecimal("92300.00").compareTo((BigDecimal) out.get("refundTax")),
                "710,000 × 13%");
    }

    @Test
    void cifDeductsFreightAndInsurance() {
        // CIF 710,000 CNY 等值：GROSS=100000 USD，运费 4000 保费 1000 → FOB 95000 USD ×7.1 = 674,500
        Map<String, Object> out = service.registerDeal(dealPayload("cif", "CIF", "100000", "4000", "1000"));
        assertEquals(0, new BigDecimal("674500.00").compareTo((BigDecimal) out.get("fobCny")));
        assertEquals(0, new BigDecimal("87685.00").compareTo((BigDecimal) out.get("refundTax")),
                "674,500 × 13%");
    }

    @Autowired
    private com.erp.service.SysParamService sysParamService;

    @Test
    void missingRefundRateBlocks() {
        // 退税率参数族缺失（C-4.14-01）——走 setValue 使进程缓存失效（SQL 直删不触发）
        sysParamService.setValue("EXPORT_REFUND_RATE_DEFAULT", "", "NUMBER", "TAX",
                "临时置空测试", "junit");
        try {
            Map<String, Object> p = dealPayload("norate", "FOB", "100000", "0", "0");
            p.put("itemCategory", "NOCAT");
            ServiceException ex = assertThrows(ServiceException.class, () -> service.registerDeal(p));
            assertTrue(ex.getMessage().contains("C-4.14-01"), ex.getMessage());
        } finally {
            sysParamService.setValue("EXPORT_REFUND_RATE_DEFAULT", "0.13", "NUMBER", "TAX",
                    "恢复默认", "junit");
        }
    }

    @Test
    void mismatchFlagsL4() {
        Map<String, Object> p = dealPayload("mm", "FOB", "100000", "0", "0");
        p.put("declAmount", "120000");
        Map<String, Object> out = service.registerDeal(p);
        assertEquals(Boolean.TRUE, out.get("mismatch"));
        assertTrue(String.valueOf(out.get("mismatchNote")).contains("不一致"));
    }

    // ================================================================
    // 5.2 单证齐全检查（BR-4.14-03 / C-4.14-13）
    // ================================================================

    @Test
    void threeDocsCompletePasses() {
        Map<String, Object> reg = service.registerDeal(dealPayload("doc3", "FOB", "100000", "0", "0"));
        String id = (String) reg.get("id");
        service.collectDocuments(id, Map.of("docDecl", true, "docInvoice", true,
                "docReceipt", true, "receiptDate", LocalDate.now().toString()));
        Map<String, Object> check = service.checkDocuments(id);
        assertEquals(Boolean.FALSE, check.get("blocked"));
        assertEquals(Boolean.TRUE, check.get("docDecl").equals("1"));
    }

    @Test
    void missingDocBlocks() {
        Map<String, Object> reg = service.registerDeal(dealPayload("doc1", "FOB", "100000", "0", "0"));
        Map<String, Object> check = service.checkDocuments((String) reg.get("id"));
        assertEquals(Boolean.TRUE, check.get("blocked"));
        @SuppressWarnings("unchecked")
        var missing = (java.util.List<String>) check.get("missing");
        assertEquals(3, missing.size(), "三单全缺");
    }

    @Test
    void nearDeadlineWarns() {
        // 截止日固定为次年 4/30（不可由登记构造临期）→ 直接 UPDATE expireDate 到 20 天后
        Map<String, Object> reg = service.registerDeal(dealPayload("near", "FOB", "100000", "0", "0"));
        String id = (String) reg.get("id");
        jdbc.update("UPDATE erp_fin_export_deal SET EXPIRE_DATE = DATE_ADD(CURDATE(), INTERVAL 20 DAY) "
                + "WHERE ID = ?", id);
        Map<String, Object> check = service.checkDocuments(id);
        assertNotNull(check.get("warning"), "临期 30 天预警");
    }

    @Test
    void pastDeadlineDeemedSaleCalculation() {
        // 报关日 2 年前 → 已过次年 4 月截止 → 视同内销测算
        Map<String, Object> p = dealPayload("past", "FOB", "100000", "0", "0");
        p.put("declDate", LocalDate.now().minusYears(2).toString());
        Map<String, Object> reg = service.registerDeal(p);
        Map<String, Object> check = service.checkDocuments((String) reg.get("id"));
        assertEquals(Boolean.TRUE, check.get("pastDeadline"));
        assertNotNull(check.get("deemedSaleTax"), "视同内销补税测算");
    }

    @Test
    void exemptionUnblocks() {
        Map<String, Object> reg = service.registerDeal(dealPayload("ex", "FOB", "100000", "0", "0"));
        String id = (String) reg.get("id");
        service.approveExemption(id, "客户确认收汇凭证下周补交，税务主管同意豁免");
        Map<String, Object> check = service.checkDocuments(id);
        assertEquals(Boolean.FALSE, check.get("blocked"), "豁免后放行");
        assertEquals(Boolean.TRUE, check.get("exempted"));
    }

    // ================================================================
    // 5.3/5.4 申报状态机 + 免抵退勾稽 + 核销 + 预警
    // ================================================================

    @Test
    void applyStateMachineAndPaidFlow() {
        Map<String, Object> reg = service.registerDeal(dealPayload("flow", "FOB", "100000", "0", "0"));
        String dealId = (String) reg.get("id");
        service.collectDocuments(dealId, Map.of("docDecl", true, "docInvoice", true, "docReceipt", true));
        Map<String, Object> apply = service.createApply(Map.of("period", "209909",
                "dealIds", dealId));
        String applyId = (String) apply.get("id");
        assertEquals(0, new BigDecimal("92300.00").compareTo((BigDecimal) apply.get("refundTotal")));
        // 档案锚回写
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_export_deal WHERE REFUND_APPLY_NO IS NOT NULL "
                        + "AND ID = ?", Integer.class, dealId));
        // L2 审批 → SUBMITTED
        service.submitApply(applyId);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(applyId), "同意申报");
        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_export_refund_apply WHERE ID = ?", String.class, applyId));
        // 状态机：受理 → 审核中 → 核准 → 到账
        service.registerStatus(applyId, Map.of("status", "ACCEPTED"));
        service.registerStatus(applyId, Map.of("status", "AUDITING"));
        service.registerStatus(applyId, Map.of("status", "APPROVED", "approvedAmount", "92300"));
        Map<String, Object> recv = service.receive(applyId,
                Map.of("receivedAmount", "92300", "receivedDate", "2099-09-20"));
        assertEquals(Boolean.TRUE, recv.get("reconciled"));
        assertEquals("PAID", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_export_refund_apply WHERE ID = ?", String.class, applyId));
    }

    @Test
    void returnedBlocksNewBatchAndResubmit() {
        Map<String, Object> reg = service.registerDeal(dealPayload("ret", "FOB", "100000", "0", "0"));
        String dealId = (String) reg.get("id");
        service.collectDocuments(dealId, Map.of("docDecl", true, "docInvoice", true, "docReceipt", true));
        Map<String, Object> apply = service.createApply(Map.of("period", "209910", "dealIds", dealId));
        String applyId = (String) apply.get("id");
        service.submitApply(applyId);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(applyId), "同意");
        service.registerStatus(applyId, Map.of("status", "ACCEPTED"));
        // 退回补正 → 挂起 + 结构化清单
        service.registerStatus(applyId, Map.of("status", "RETURNED", "returnReason", "收汇未核销"));
        var ret = jdbc.queryForMap(
                "SELECT STATUS, RETURN_CORRECTIONS_JSON FROM erp_fin_export_refund_apply WHERE ID = ?",
                applyId);
        assertEquals("RETURNED", ret.get("STATUS"));
        assertTrue(String.valueOf(ret.get("RETURN_CORRECTIONS_JSON")).contains("收汇未核销"));
        // 挂起时暂停新批次
        Map<String, Object> reg2 = service.registerDeal(dealPayload("ret2", "FOB", "50000", "0", "0"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.createApply(Map.of("period", "209910", "dealIds", reg2.get("id"))));
        assertTrue(ex.getMessage().contains("挂起"), ex.getMessage());
        // 补正后重提
        service.registerStatus(applyId, Map.of("status", "SUBMITTED"));
        assertEquals("SUBMITTED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_export_refund_apply WHERE ID = ?", String.class, applyId));
    }

    @Test
    void mtdCrossCheckBlocksOnMismatch() {
        Map<String, Object> reg = service.registerDeal(dealPayload("mtd", "FOB", "100000", "0", "0"));
        String dealId = (String) reg.get("id");
        service.collectDocuments(dealId, Map.of("docDecl", true, "docInvoice", true, "docReceipt", true));
        // 同期 VAT 申报单 PAYABLE_TAX = 24700
        jdbc.update("INSERT INTO erp_fin_tax_return (ID, RETURN_NO, PERIOD, TAX_TYPE, STATUS, "
                + "OUTPUT_TAX, INPUT_TAX, TRANSFER_OUT, PREV_CREDIT, PAYABLE_TAX, END_CREDIT, "
                + "RECON_STATUS, LOCK_FLAG, OVERDUE_FLAG, ESCALATED_FLAG, ADJUSTED_FLAG, CREATE_BY, "
                + "DEL_FLAG, VER_NO) VALUES ('smkexp-vat','TRET209909-0001','209909','VAT','APPROVED',"
                + "65000,39000,1300,2600,24700,0,'PASSED','1','0','0','0','junit','0',0)");
        // 免抵退勾稽不一致 → L1 阻断（BR-44）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.createApply(Map.of("period", "209909", "dealIds", dealId,
                        "mtdPayable", "25000", "vatReturnId", "smkexp-vat")));
        assertTrue(ex.getMessage().contains("BR-4.14-44"), ex.getMessage());
        // 一致 → 放行
        Map<String, Object> ok = service.createApply(Map.of("period", "209909", "dealIds", dealId,
                "mtdPayable", "24700", "vatReturnId", "smkexp-vat"));
        assertNotNull(ok.get("id"));
    }

    @Test
    void receiveDiffHoldsReconcile() {
        Map<String, Object> reg = service.registerDeal(dealPayload("diff", "FOB", "100000", "0", "0"));
        String dealId = (String) reg.get("id");
        service.collectDocuments(dealId, Map.of("docDecl", true, "docInvoice", true, "docReceipt", true));
        Map<String, Object> apply = service.createApply(Map.of("period", "209911", "dealIds", dealId));
        String applyId = (String) apply.get("id");
        service.submitApply(applyId);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(applyId), "同意");
        service.registerStatus(applyId, Map.of("status", "ACCEPTED"));
        service.registerStatus(applyId, Map.of("status", "AUDITING"));
        service.registerStatus(applyId, Map.of("status", "APPROVED", "approvedAmount", "92300"));
        // 到账 90,000 vs 核准 92,300（差 2,300 > 容差）→ 挂账
        Map<String, Object> recv = service.receive(applyId,
                Map.of("receivedAmount", "90000", "receivedDate", "2099-09-25"));
        assertEquals(Boolean.FALSE, recv.get("reconciled"));
        assertNotNull(recv.get("reconcileTaskId"));
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_export_refund_apply WHERE ID = ?", String.class, applyId));
    }

    @Test
    void warningsTwoLevels() {
        // 截止日由登记固定为次年 4/30 → 直接 UPDATE expireDate 构造三档窗口
        Map<String, Object> l1 = service.registerDeal(dealPayload("w1", "FOB", "100000", "0", "0"));
        jdbc.update("UPDATE erp_fin_export_deal SET EXPIRE_DATE = DATE_ADD(CURDATE(), INTERVAL 45 DAY) "
                + "WHERE ID = ?", l1.get("id"));
        Map<String, Object> l2 = service.registerDeal(dealPayload("w2", "FOB", "100000", "0", "0"));
        jdbc.update("UPDATE erp_fin_export_deal SET EXPIRE_DATE = DATE_ADD(CURDATE(), INTERVAL 20 DAY) "
                + "WHERE ID = ?", l2.get("id"));
        Map<String, Object> over = service.registerDeal(dealPayload("w3", "FOB", "100000", "0", "0"));
        jdbc.update("UPDATE erp_fin_export_deal SET EXPIRE_DATE = DATE_SUB(CURDATE(), INTERVAL 5 DAY) "
                + "WHERE ID = ?", over.get("id"));
        Map<String, Object> out = service.warnings();
        assertTrue(((Number) out.get("level1Count")).intValue() >= 1, "一级预警");
        assertTrue(((Number) out.get("level2Count")).intValue() >= 1, "二级预警");
        assertTrue(((Number) out.get("overdueCount")).intValue() >= 1, "超期清单");
    }
}
