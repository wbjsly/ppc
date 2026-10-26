package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.TaxReturnDao;
import com.erp.entity.fin.TaxReturn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 税务申报 DbTest（add-tax-filing：spec tax-filing + export-tax-refund）。
 * 共享库隔离：整域清理（同 RedFlushVoidDbTest 先例）+ junit 造数 + 期间 209910。
 * 1.3 骨架：申报单实体落库；后续任务扩展申报/退税/出口场景。
 */
@SpringBootTest
class TaxFilingDbTest {

    @Autowired
    private TaxReturnDao returnDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_FINANCE"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE IN ('TAXFILE','TAXREFUND','EXPREFUND')");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('TAXFILE','TAXREFUND','EXPREFUND')");
        jdbc.update("DELETE FROM erp_fin_tax_return_line");
        jdbc.update("DELETE FROM erp_fin_tax_return_adj WHERE ADJ_NO LIKE 'TADJ%' AND CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_fin_tax_return WHERE RETURN_NO LIKE 'TRET%' AND CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_fin_tax_refund_apply WHERE APPLY_NO LIKE 'RVAP%' AND CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_fin_export_refund_apply WHERE APPLY_NO LIKE 'RFA%' AND CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_fin_export_deal WHERE DEAL_NO LIKE 'EXP%' AND CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_fin_sales_invoice WHERE ID LIKE 'smkrf-tax-%'");
        jdbc.update("DELETE FROM erp_fin_input_invoice WHERE ID LIKE 'smkrf-tax-%'");
        jdbc.update("DELETE FROM erp_fin_balance WHERE ID LIKE 'smkrf-bal-%' OR ID LIKE 'smkrf-cit-%'");
        // 残留隔离：其他套件在 2099 合成期间的损益余额行会污染 CIT 利润聚合（共享库先例）
        jdbc.update("DELETE FROM erp_fin_balance WHERE ACCOUNT_CODE LIKE '6%' AND PERIOD LIKE '2099%'");
    }

    // ================================================================
    // 1.3 骨架：申报单实体落库 + 状态字段
    // ================================================================

    @Test
    void entityRoundTrip() {
        TaxReturn r = new TaxReturn();
        r.setReturnNo("TRET209910-0001");
        r.setPeriod("209910");
        r.setTaxType(TaxReturn.TYPE_VAT);
        r.setStatus(TaxReturn.ST_DRAFT);
        r.setOutputTax(new BigDecimal("65000.00"));
        r.setInputTax(new BigDecimal("39000.00"));
        r.setTransferOut(new BigDecimal("1300.00"));
        r.setPrevCredit(new BigDecimal("2600.00"));
        r.setPayableTax(new BigDecimal("24700.00"));
        r.setReconStatus(TaxReturn.RECON_PENDING);
        r.setLockFlag("0");
        r.setCreateBy("junit");
        returnDao.insert(r);

        TaxReturn loaded = returnDao.selectById(r.getId());
        assertNotNull(loaded);
        assertEquals("TRET209910-0001", loaded.getReturnNo());
        assertEquals(TaxReturn.ST_DRAFT, loaded.getStatus());
        assertEquals(0, new BigDecimal("24700.00").compareTo(loaded.getPayableTax()));
        assertEquals("0", loaded.getLockFlag());
    }

    // ================================================================
    // 2.1~2.5 增值税申报链（汇总/勾稽/生成/审批锁定/缴款逾期）
    // ================================================================

    @Autowired
    private com.erp.service.fin.TaxFilingService taxFilingService;
    @Autowired
    private com.erp.service.approval.ApprovalEngine approvalEngine;

    private static final String P = "209910";

    /** 造对平数据：销项 13% 票税额 65000；进项认证税额 39000；转出 1300；账载同额 POSTED */
    private void seedBalanced(String suffix) {
        // 销项：一张 13% 蓝票税额 65000
        jdbc.update("INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, "
                + "INVOICE_DATE, AMOUNT, NET_AMOUNT, TAX_RATE, TAX_AMOUNT, STATUS, RED_AMOUNT, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'smk-cust', '申报客户', '2099-10-10', "
                + "565000, 500000, 0.13, 65000, 'ISSUED', 0, 'junit', '0', 0)",
                "smkrf-tax-inv-" + suffix, "SMKTAX-FP-" + suffix);
        // 进项：已认证税额 39000 + 转出 1300
        jdbc.update("INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, "
                + "CERT_DATE, CERT_PERIOD, DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'smk-sup', '申报供应商', '2099-10-05', 300000, 39000, 'smk-po', 'SMKTAXPO', "
                + "'MATCHED', 'CERTIFIED', '2099-10-06', '209910', 'Y', 1300, 'junit', '0', 0)",
                "smkrf-tax-in-" + suffix, "SMKTAX-TIN-" + suffix);
        // 账载 POSTED：222102 贷 65000 / 222101 借 39000 / 222103 贷 1300（对平）
        jdbc.update("INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "(CONCAT('smkrf-bal-ou-', ?), ?, '222102', '', 0, 65000, 'junit', NOW(), '0', 0), "
                + "(CONCAT('smkrf-bal-in-', ?), ?, '222101', '', 39000, 0, 'junit', NOW(), '0', 0), "
                + "(CONCAT('smkrf-bal-to-', ?), ?, '222103', '', 0, 1300, 'junit', NOW(), '0', 0)",
                suffix, P, suffix, P, suffix, P);
    }

    private String activeTaskId(String bizId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                        + "WHERE i.BIZ_ID = ? AND t.STATUS = 'ACTIVE' LIMIT 1", String.class, bizId);
    }

    private static void loginRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, role));
    }

    @Test
    void summarizeClassifiedCorrectly() {
        seedBalanced("sum");
        Map<String, Object> out = taxFilingService.summarize(P);
        assertEquals(0, new BigDecimal("65000.00").compareTo((BigDecimal) out.get("outputTax")));
        assertEquals(0, new BigDecimal("39000.00").compareTo((BigDecimal) out.get("inputTax")));
        assertEquals(0, new BigDecimal("1300.00").compareTo((BigDecimal) out.get("transferOut")));
    }

    @Test
    void reconcilePassesWhenBalanced() {
        seedBalanced("rec");
        Map<String, Object> out = taxFilingService.reconcile(P);
        assertEquals(Boolean.TRUE, out.get("passed"));
    }

    @Test
    void reconcileDiffBlocksGenerate() {
        // 账载少 1300（63700 vs 65000）→ BR-4.14-30 栏次级差异
        seedBalanced("diff");
        jdbc.update("UPDATE erp_fin_balance SET CR_TOTAL = 63700 WHERE ID = 'smkrf-bal-ou-diff'");
        Map<String, Object> rec = taxFilingService.reconcile(P);
        assertEquals(Boolean.FALSE, rec.get("passed"));
        ServiceException ex = assertThrows(ServiceException.class, () -> taxFilingService.generate(P));
        assertTrue(ex.getMessage().contains("C-4.14-04"), ex.getMessage());
        assertTrue(ex.getMessage().contains("销项税额"), "差异报告含栏次名");
        assertTrue(ex.getMessage().contains("1300"), "差异报告含差额");
    }

    @Test
    void generateAndPayableFormula() {
        seedBalanced("gen");
        Map<String, Object> out = taxFilingService.generate(P);
        // 65000 − 39000 + 1300 − 0（无上期留抵）= 27300
        assertEquals(0, new BigDecimal("27300.00").compareTo((BigDecimal) out.get("payableTax")));
        // 栏次：主表 + 附表1 + 附表2 + 附加税三条
        int lines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_tax_return_line WHERE RETURN_ID = ?", Integer.class,
                out.get("id"));
        assertTrue(lines >= 7, "栏次覆盖主表/附表/附加税（实际 " + lines + "）");
        // 重复生成 409
        assertThrows(ServiceException.class, () -> taxFilingService.generate(P));
    }

    @Test
    void negativePayableCarriesCredit() {
        // 销项 20000 − 进项 35000 → 负值结转留抵（BR-4.14-32）
        jdbc.update("INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, "
                + "INVOICE_DATE, AMOUNT, NET_AMOUNT, TAX_RATE, TAX_AMOUNT, STATUS, RED_AMOUNT, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('smkrf-tax-inv-neg','SMKTAX-FP-NEG','smk-cust',"
                + "'申报客户','2099-10-10',226000,200000,0.13,20000,'ISSUED',0,'junit','0',0)");
        jdbc.update("INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, "
                + "CERT_DATE, CERT_PERIOD, DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('smkrf-tax-in-neg','SMKTAX-TIN-NEG','smk-sup','申报供应商','2099-10-05',"
                + "269000,35000,'smk-po','SMKTAXPO','MATCHED','CERTIFIED','2099-10-06','209910','Y',0,"
                + "'junit','0',0)");
        jdbc.update("INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('smkrf-bal-ou-neg','209910','222102','',0,20000,'junit',NOW(),'0',0), "
                + "('smkrf-bal-in-neg','209910','222101','',35000,0,'junit',NOW(),'0',0)");
        Map<String, Object> out = taxFilingService.generate(P);
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) out.get("payableTax")));
        assertEquals(0, new BigDecimal("15000.00").compareTo((BigDecimal) out.get("endCredit")));
        // 无退税自动申请
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_tax_refund_apply WHERE DEL_FLAG='0'", Integer.class));
    }

    @Test
    void zeroPayableZeroesSurcharge() {
        // 主税为零 → 附加税三税全零（BR-4.14-34）：销项 20000，进项 21300（无转出/留抵）→ 负值结转
        jdbc.update("INSERT INTO erp_fin_sales_invoice (ID, INVOICE_NO, CUSTOMER_ID, CUSTOMER_NAME, "
                + "INVOICE_DATE, AMOUNT, NET_AMOUNT, TAX_RATE, TAX_AMOUNT, STATUS, RED_AMOUNT, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('smkrf-tax-inv-zero','SMKTAX-FP-ZERO','smk-cust',"
                + "'申报客户','2099-10-10',226000,200000,0.13,20000,'ISSUED',0,'junit','0',0)");
        jdbc.update("INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, "
                + "CERT_DATE, CERT_PERIOD, DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('smkrf-tax-in-zero','SMKTAX-TIN-ZERO','smk-sup','申报供应商','2099-10-05',"
                + "190000,21300,'smk-po','SMKTAXPO','MATCHED','CERTIFIED','2099-10-06','209910','Y',0,"
                + "'junit','0',0)");
        jdbc.update("INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('smkrf-bal-ou-zero','209910','222102','',0,20000,'junit',NOW(),'0',0), "
                + "('smkrf-bal-in-zero','209910','222101','',21300,0,'junit',NOW(),'0',0)");
        Map<String, Object> out = taxFilingService.generate(P);
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) out.get("payableTax")));
        assertEquals(0, new BigDecimal("1300.00").compareTo((BigDecimal) out.get("endCredit")));
        var surcharge = jdbc.queryForList(
                "SELECT LINE_CODE, AMOUNT FROM erp_fin_tax_return_line WHERE RETURN_ID = ? "
                        + "AND LINE_CODE LIKE '附加税-%'", out.get("id"));
        assertEquals(3, surcharge.size());
        for (var row : surcharge) {
            assertEquals(0, new BigDecimal("0.00").compareTo(new BigDecimal(String.valueOf(row.get("AMOUNT")))),
                    "主税为零附加税须为零：" + row.get("LINE_CODE"));
        }
    }

    @Test
    void threeLevelApprovalAndLock() {
        seedBalanced("appr");
        Map<String, Object> gen = taxFilingService.generate(P);
        String id = (String) gen.get("id");
        Map<String, Object> sub = taxFilingService.submit(id);
        assertEquals(3, sub.get("chainSize"), "三级审批链");
        // 三级依次通过
        loginRole("ROLE_FINANCE");
        approvalEngine.pass(activeTaskId(id), "专员复核通过");
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "主管审核通过");
        loginRole("ROLE_FIN_MGR");
        approvalEngine.pass(activeTaskId(id), "经理终审通过");
        TaxReturn r = returnDao.selectById(id);
        assertEquals(TaxReturn.ST_APPROVED, r.getStatus());
        assertEquals("1", r.getLockFlag(), "审批通过即锁定");
        // 锁定期直接修改走调整单（generate 409）
        assertThrows(ServiceException.class, () -> taxFilingService.generate(P));
    }

    @Test
    void crossPeriodAdjustmentApplies() {
        seedBalanced("adj");
        Map<String, Object> gen = taxFilingService.generate(P);
        String id = (String) gen.get("id");
        taxFilingService.submit(id);
        loginRole("ROLE_FINANCE");
        approvalEngine.pass(activeTaskId(id), "专员复核");
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "主管审核");
        loginRole("ROLE_FIN_MGR");
        approvalEngine.pass(activeTaskId(id), "经理终审");
        // 跨期调整：销项改为 63700（更正申报场景）
        Map<String, Object> adj = taxFilingService.adjust(id, Map.of(
                "outputTax", "63700", "reason", "跨期更正：销项归属调整，已评估需更正申报"));
        String adjId = (String) adj.get("adjId");
        loginRole("ROLE_FINANCE");
        approvalEngine.pass(activeTaskId(adjId), "确认归属期");
        loginRole("ROLE_FIN_MGR");
        approvalEngine.pass(activeTaskId(adjId), "批准调整");
        TaxReturn r = returnDao.selectById(id);
        assertEquals(0, new BigDecimal("63700.00").compareTo(r.getOutputTax()));
        assertEquals("1", r.getAdjustedFlag());
        assertEquals(0, new BigDecimal("26000.00").compareTo(r.getPayableTax()), "63700−39000+1300=26000");
        assertEquals(TaxReturn.RECON_BLOCKED, r.getReconStatus(), "调整后须重新勾稽");
        // 调整记录留痕
        var adjRow = jdbc.queryForMap(
                "SELECT BEFORE_JSON, AFTER_JSON, STATUS FROM erp_fin_tax_return_adj WHERE ID = ?", adjId);
        assertEquals("APPROVED", adjRow.get("STATUS"));
        assertTrue(String.valueOf(adjRow.get("BEFORE_JSON")).contains("65000"), "调整前快照");
    }

    @Test
    void payChainAndOverdue() {
        seedBalanced("pay");
        Map<String, Object> gen = taxFilingService.generate(P);
        String id = (String) gen.get("id");
        taxFilingService.submit(id);
        loginRole("ROLE_FINANCE");
        approvalEngine.pass(activeTaskId(id), "专员复核");
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "主管审核");
        loginRole("ROLE_FIN_MGR");
        approvalEngine.pass(activeTaskId(id), "经理终审");
        // 未审批前不可登记缴款 → 已审批
        // 顺序校验：先缴款 422（未登记回执）
        assertThrows(ServiceException.class, () -> taxFilingService.registerStatus(id,
                Map.of("payDate", "2099-11-20", "payAmount", "27300")));
        // 正常链：提交→回执→缴款
        taxFilingService.registerStatus(id, Map.of("submitDate", "2099-11-13"));
        taxFilingService.registerStatus(id, Map.of("receiptNo", "HZ20991113001", "fileDate", "2099-11-13"));
        Map<String, Object> pay = taxFilingService.registerStatus(id,
                Map.of("payDate", "2099-11-20", "payAmount", "27300", "payVoucherNo", "PAY-001"));
        assertEquals("PAID", pay.get("status"));
        // 已申报不构成逾期
        assertThrows(ServiceException.class, () -> taxFilingService.markOverdue(id, "故障"));
    }

    @Test
    void overdueEstimatesLateFee() {
        // 过去所属期（202601，截止 2026-02-15 已过）→ 滞纳金 > 0
        jdbc.update("INSERT INTO erp_fin_tax_return (ID, RETURN_NO, PERIOD, TAX_TYPE, STATUS, "
                + "OUTPUT_TAX, INPUT_TAX, TRANSFER_OUT, PREV_CREDIT, PAYABLE_TAX, END_CREDIT, "
                + "RECON_STATUS, LOCK_FLAG, OVERDUE_FLAG, ESCALATED_FLAG, ADJUSTED_FLAG, CREATE_BY, "
                + "DEL_FLAG, VER_NO) VALUES ('smkrf-od','TRET202601-0001','202601','VAT','DRAFT',"
                + "65000,39000,0,0,27300,0,'PASSED','0','0','0','0','junit','0',0)");
        Map<String, Object> out = taxFilingService.markOverdue("smkrf-od", "电子税务局通道故障");
        assertTrue(((BigDecimal) out.get("lateFee")).signum() > 0, "滞纳金>0");
        assertTrue(((Number) out.get("overdueDays")).longValue() > 0, "滞纳天数>0");
        TaxReturn r = returnDao.selectById("smkrf-od");
        assertEquals(TaxReturn.ST_OVERDUE, r.getStatus());
        assertEquals("1", r.getEscalatedFlag(), "已升级财务经理");
        assertNotNull(r.getFaultNote());
        // 缺故障说明 422
        assertThrows(ServiceException.class, () -> taxFilingService.markOverdue("smkrf-od", " "));
    }

    // ================================================================
    // 3.1/3.2 留抵退税（BR-4.14-33 / S-4.14-15 全链）
    // ================================================================

    @Autowired
    private com.erp.service.fin.TaxRefundService taxRefundService;

    /** S-4.14-15 造数：增量留抵 2,450,000 / 构成 92% / 第 6 月 620,000 / A 级 */
    private Map<String, Object> ldtiPayload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("period", "209906");
        p.put("creditLevel", "A");
        p.put("incrCredit", "2450000");
        p.put("constitutionRatio", "0.92");
        p.put("month6Incr", "620000");
        p.put("sixMonthTotal", "2450000");
        return p;
    }

    @Test
    void ldtiAllConditionsPassCalculatesRefund() {
        // S-4.14-15：2,450,000 × 92% × 100% = 2,254,000
        Map<String, Object> out = taxRefundService.apply(ldtiPayload());
        assertEquals(0, new BigDecimal("2254000.00").compareTo((BigDecimal) out.get("allowedRefund")));
        assertEquals("DRAFT", out.get("status"));
    }

    @Test
    void ldtiUnmetConditionsBlockListingAll() {
        Map<String, Object> p = ldtiPayload();
        p.put("creditLevel", "C");
        p.put("month6Incr", "200000");
        ServiceException ex = assertThrows(ServiceException.class, () -> taxRefundService.apply(p));
        assertTrue(ex.getMessage().contains("BR-4.14-33"), ex.getMessage());
        assertTrue(ex.getMessage().contains("纳税信用等级"), "列条件①");
        assertTrue(ex.getMessage().contains("低于规定金额"), "列条件③");
        // 构成比例 92% 仍满足 → 不应列④
        assertFalse(ex.getMessage().contains("进项构成比例"), "不误列条件④");
    }

    @Test
    void ldtiFullChainWithReturnCorrectAndReceive() {
        Map<String, Object> out = taxRefundService.apply(ldtiPayload());
        String id = (String) out.get("id");
        taxRefundService.submit(id);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "同意留抵退税");
        // 内部核准
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_tax_refund_apply WHERE ID = ?", String.class, id));
        // 受理 → 退回补正（S-4.14-15：合同未上传）→ 重提 → 核准
        taxRefundService.registerStatus(id, Map.of("status", "ACCEPTED"));
        taxRefundService.registerStatus(id, Map.of("status", "RETURNED",
                "returnReason", "部分进项发票对应采购合同未上传"));
        var returned = jdbc.queryForMap(
                "SELECT STATUS, CORRECTIONS_JSON FROM erp_fin_tax_refund_apply WHERE ID = ?", id);
        assertEquals("RETURNED", returned.get("STATUS"));
        assertTrue(String.valueOf(returned.get("CORRECTIONS_JSON")).contains("合同"), "补正清单留痕");
        taxRefundService.registerStatus(id, Map.of("status", "ACCEPTED"));
        taxRefundService.registerStatus(id, Map.of("status", "APPROVED", "approvedAmount", "2254000"));
        // 到账一致 → 自动核销 + 收款凭证
        Map<String, Object> recv = taxRefundService.receive(id, Map.of(
                "receivedAmount", "2254000", "receivedDate", "2099-08-06"));
        assertEquals(Boolean.TRUE, recv.get("reconciled"));
        assertNotNull(recv.get("voucherId"));
        assertEquals("PAID", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_tax_refund_apply WHERE ID = ?", String.class, id));
        // 收款凭证行：借 1002 / 贷 222101
        int lines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher_line WHERE VOUCHER_ID = ?",
                Integer.class, recv.get("voucherId"));
        assertEquals(2, lines);
    }

    @Test
    void ldtiReceiveDiffHoldsForReconcile() {
        Map<String, Object> out = taxRefundService.apply(ldtiPayload());
        String id = (String) out.get("id");
        taxRefundService.submit(id);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "同意");
        taxRefundService.registerStatus(id, Map.of("status", "ACCEPTED"));
        taxRefundService.registerStatus(id, Map.of("status", "APPROVED"));
        // 到账 2,250,000 vs 核准 2,254,000（差 4,000 > 容差）→ 挂账不核销
        Map<String, Object> recv = taxRefundService.receive(id, Map.of(
                "receivedAmount", "2250000", "receivedDate", "2099-08-06"));
        assertEquals(Boolean.FALSE, recv.get("reconciled"));
        assertNotNull(recv.get("reconcileTaskId"));
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_tax_refund_apply WHERE ID = ?", String.class, id),
                "差异挂账不核销");
    }

    // ================================================================
    // 4.1/4.2 企业所得税季报预缴（累计利润×税率+调整项）
    // ================================================================

    @Test
    void citCumulativePrepayCalculation() {
        // 账载利润 2,000,000（6001 贷方 2,100,000 − 6401 借方 100,000）× 25% = 525,000 − 已预缴 450,000 = 75,000
        jdbc.update("INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('smkrf-cit-rev','209909','6001','',0,2100000,'junit',NOW(),'0',0), "
                + "('smkrf-cit-cost','209909','6401','',100000,0,'junit',NOW(),'0',0)");
        Map<String, Object> out = taxFilingService.generateCit("209909",
                Map.of("citAdjust", "100000", "citPrepaid", "450000"));
        // (2,000,000 + 100,000) × 25% − 450,000 = 75,000
        assertEquals(0, new BigDecimal("75000.00").compareTo((BigDecimal) out.get("citDue")));
        assertEquals("DRAFT", out.get("status"));
        // 栏次留痕
        int lines = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_tax_return_line WHERE RETURN_ID = ?", Integer.class,
                out.get("id"));
        assertEquals(5, lines, "CIT 栏次五条");
    }

    @Test
    void citLossQuarterZeroesWithoutRefund() {
        // 累计亏损 → 应纳 0，已预缴不退（spec：亏损季度零申报）
        jdbc.update("INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('smkrf-cit-loss','209909','6401','',500000,0,'junit',NOW(),'0',0), "
                + "('smkrf-cit-lossrev','209909','6001','',0,200000,'junit',NOW(),'0',0)");
        Map<String, Object> out = taxFilingService.generateCit("209909",
                Map.of("citPrepaid", "100000"));
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) out.get("citDue")));
    }

    @Test
    void citManualBaseMismatchBlocks() {
        // 手工录入与账载不一致 → C-4.14-04 同口径阻断
        jdbc.update("INSERT INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES "
                + "('smkrf-cit-m','209909','6001','',0,2000000,'junit',NOW(),'0',0)");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> taxFilingService.generateCit("209909", Map.of("citBase", "3000000")));
        assertTrue(ex.getMessage().contains("C-4.14-04"), ex.getMessage());
        assertTrue(ex.getMessage().contains("利润总额"), "差异栏次为利润总额");
    }
}
