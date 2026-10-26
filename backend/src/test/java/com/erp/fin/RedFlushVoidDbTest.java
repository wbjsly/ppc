package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.RedFlushApplyDao;
import com.erp.dao.fin.RedInvoiceDao;
import com.erp.dao.fin.SalesInvoiceDao;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.RedFlushApply;
import com.erp.entity.fin.RedInvoice;
import com.erp.entity.fin.SalesInvoice;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.fin.RedFlushService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 红冲作废 DbTest（add-red-invoice-void：spec red-invoice-void 7 需求 24 场景）。
 * 共享库隔离：整域清理（同 InvoiceManagementDbTest 先例）+ junit 造数。
 * 覆盖：2.1 申请校验 / 2.2 分级审批 / 2.3 回调信息单 / 3.1 编号门禁 / 3.2 开票联动 / 3.3 凭证。
 */
@SpringBootTest
class RedFlushVoidDbTest {

    @Autowired
    private RedFlushService redFlushService;
    @Autowired
    private RedFlushApplyDao applyDao;
    @Autowired
    private RedInvoiceDao redDao;
    @Autowired
    private SalesInvoiceDao invoiceDao;
    @Autowired
    private ArInvoiceDao arDao;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_FINANCE");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_fin_voucher_line t JOIN erp_fin_voucher v ON v.ID = t.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE IN ('REDFLUSH','VOIDINV','TAXOUT')");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('REDFLUSH','VOIDINV','TAXOUT')");
        jdbc.update("DELETE FROM erp_fin_red_flush_apply");
        jdbc.update("DELETE FROM erp_fin_red_invoice WHERE RED_NO LIKE 'FPZ%' OR FLUSH_APPLY_NO IS NOT NULL");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                + "WHERE i.BIZ_TYPE IN ('RedFlush','RedInvoiceVoid')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('RedFlush','RedInvoiceVoid')");
        jdbc.update("DELETE FROM erp_fin_sales_invoice WHERE ID LIKE 'junit-rf-%'");
        jdbc.update("DELETE FROM erp_fin_ar_invoice WHERE ID LIKE 'junit-rf-ar-%'");
        jdbc.update("DELETE FROM erp_sd_invoice_apply WHERE ID LIKE 'junit-rf-apply-%'");
        jdbc.update("DELETE FROM erp_fin_input_invoice WHERE ID LIKE 'junit-rf-in-%'");
        jdbc.update("DELETE FROM erp_fin_invoice_void_apply WHERE APPLY_NO LIKE 'VSAP%' "
                + "AND CREATE_BY IN ('junit','approval')");
        jdbc.update("DELETE FROM erp_fin_invoice_delivery WHERE ID LIKE 'junit-vd-%'");
    }

    private static void loginRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, role));
    }

    /** 造原蓝票：金额 100000、税率 13%、AR 100000 未收 */
    private SalesInvoice seedInvoice(String suffix, String invoiceDate) {
        String id = "junit-rf-inv-" + suffix;
        String arId = "junit-rf-ar-" + suffix;
        jdbc.update("INSERT INTO erp_fin_ar_invoice (ID, AR_NO, CUSTOMER_ID, CUSTOMER_NAME, INVOICE_DATE, "
                + "DUE_DATE, AMOUNT, PAID_AMOUNT, RED_AMOUNT, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, ?, ?, 100000, 0, 0, 'UNPAID', 'junit', '0', 0)",
                arId, "JUNITAR-" + suffix, "junit-rf-cust-" + suffix, "红冲客户" + suffix,
                invoiceDate, invoiceDate);
        SalesInvoice inv = new SalesInvoice();
        inv.setId(id);
        inv.setInvoiceNo("JUNITFP-" + suffix);
        inv.setCustomerId("junit-rf-cust-" + suffix);
        inv.setCustomerName("红冲客户" + suffix);
        inv.setInvoiceDate(java.time.LocalDate.parse(invoiceDate));
        inv.setAmount(new BigDecimal("100000"));
        inv.setNetAmount(new BigDecimal("88495.58"));
        inv.setTaxRate(new BigDecimal("0.13"));
        inv.setTaxAmount(new BigDecimal("11504.42"));
        inv.setStatus(SalesInvoice.ST_ISSUED);
        inv.setArId(arId);
        inv.setArNo("JUNITAR-" + suffix);
        inv.setRedAmount(BigDecimal.ZERO);
        inv.setCreateBy("junit");
        invoiceDao.insert(inv);
        return inv;
    }

    private Map<String, Object> payload(SalesInvoice inv, String amount) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("originInvoiceId", inv.getId());
        p.put("amount", amount);
        p.put("reasonType", "ERROR");
        p.put("reason", "开票错误测试");
        return p;
    }

    private String activeTaskId(String bizId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                        + "WHERE i.BIZ_ID = ? AND t.STATUS = 'ACTIVE' LIMIT 1", String.class, bizId);
    }

    // ================================================================
    // 2.1 申请创建校验（C-4.14-02 / C-4.14-12 / 跨期打标）
    // ================================================================

    @Test
    void amountOverRemainingBlocked() {
        SalesInvoice inv = seedInvoice("over", "2026-10-01");
        // 已红冲 30000 → 剩余 70000，申请 80000 阻断
        inv.setRedAmount(new BigDecimal("30000"));
        invoiceDao.updateById(inv);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redFlushService.create(payload(inv, "80000")));
        assertTrue(ex.getMessage().contains("剩余可红冲金额 70000"), ex.getMessage());
    }

    @Test
    void fullyRedFlushedBlocked() {
        SalesInvoice inv = seedInvoice("full", "2026-10-01");
        inv.setRedAmount(new BigDecimal("100000"));
        inv.setStatus(SalesInvoice.ST_RED_FULL);
        invoiceDao.updateById(inv);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redFlushService.create(payload(inv, "1000")));
        assertTrue(ex.getMessage().contains("已全额红冲"), ex.getMessage());
    }

    @Test
    void voidOriginBlocked() {
        SalesInvoice inv = seedInvoice("void", "2026-10-01");
        inv.setStatus(SalesInvoice.ST_VOID);
        invoiceDao.updateById(inv);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redFlushService.create(payload(inv, "1000")));
        assertTrue(ex.getMessage().contains("已作废"), ex.getMessage());
    }

    @Test
    void createSuccessWithCrossPeriodFlag() {
        // 原票 2026-05 vs 当前税期 → 跨期打标
        SalesInvoice inv = seedInvoice("cross", "2026-05-18");
        Map<String, Object> out = redFlushService.create(payload(inv, "50000"));
        assertEquals(Boolean.TRUE, out.get("crossPeriod"));
        RedFlushApply a = applyDao.selectById((String) out.get("id"));
        assertEquals("1", a.getCrossPeriodFlag());
        assertEquals(RedFlushApply.ST_DRAFT, a.getStatus());
    }

    @Test
    void samePeriodNoCrossFlag() {
        SalesInvoice inv = seedInvoice("same", java.time.LocalDate.now().toString());
        Map<String, Object> out = redFlushService.create(payload(inv, "50000"));
        assertEquals(Boolean.FALSE, out.get("crossPeriod"));
    }

    // ================================================================
    // 2.2 分级审批（金额阈值 + 频繁红冲升级）
    // ================================================================

    @Test
    void belowThresholdSingleNode() {
        SalesInvoice inv = seedInvoice("low", "2026-10-01");
        Map<String, Object> created = redFlushService.create(payload(inv, "50000"));
        Map<String, Object> out = redFlushService.submit((String) created.get("id"));
        assertEquals(Boolean.FALSE, out.get("overThreshold"));
        assertEquals(1, out.get("chainSize"));
    }

    @Test
    void overThresholdAddsFinanceMgr() {
        SalesInvoice inv = seedInvoice("high", "2026-10-01");
        // 阈值 100000，整票 100000（>= 判定）→ 超阈值双级
        Map<String, Object> created = redFlushService.create(payload(inv, "100000"));
        Map<String, Object> out = redFlushService.submit((String) created.get("id"));
        assertEquals(Boolean.TRUE, out.get("overThreshold"));
        assertEquals(2, out.get("chainSize"));
    }

    @Test
    void freqUpgradeAppendsFinanceMgr() {
        // 同客户 3 张历史红冲单（ISSUED）→ 窗口内 3 单 ≥ 阈值 3 → 升级
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO erp_fin_red_flush_apply (ID, APPLY_NO, ORIGIN_INVOICE_ID, "
                    + "CUSTOMER_ID, CUSTOMER_NAME, AMOUNT, REASON_TYPE, REASON, STATUS, "
                    + "CROSS_PERIOD_FLAG, FREQ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                    + "VALUES (?, ?, 'x', 'junit-rf-freq', '频繁客户', 1000, 'ERROR', '历史', 'ISSUED', "
                    + "'0', '0', 'junit', DATE_SUB(NOW(), INTERVAL " + i + " DAY), '0', 0)",
                    "junit-rf-hist-" + i, "FSAPHIST0" + i);
        }
        SalesInvoice inv = seedInvoice("freq", "2026-10-01");
        inv.setCustomerId("junit-rf-freq");
        inv.setCustomerName("频繁客户");
        invoiceDao.updateById(inv);
        Map<String, Object> created = redFlushService.create(payload(inv, "50000"));
        Map<String, Object> out = redFlushService.submit((String) created.get("id"));
        assertEquals(Boolean.TRUE, out.get("freqUpgrade"));
        assertEquals(2, out.get("chainSize"), "低于金额阈值但频繁 → 追加财务经理节点");
    }

    @Test
    void rejectLeavesTrace() {
        SalesInvoice inv = seedInvoice("rej", "2026-10-01");
        Map<String, Object> created = redFlushService.create(payload(inv, "50000"));
        String id = (String) created.get("id");
        redFlushService.submit(id);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.reject(activeTaskId(id), "原因不充分，驳回");
        RedFlushApply a = applyDao.selectById(id);
        assertEquals(RedFlushApply.ST_REJECTED, a.getStatus());
    }

    // ================================================================
    // 2.3 审批回调：信息单生成 + 填开方判定 + 跨期评估校验
    // ================================================================

    @Test
    void approvedGeneratesInfoSheetSellerFiller() {
        SalesInvoice inv = seedInvoice("seller", "2026-10-01");
        Map<String, Object> created = redFlushService.create(payload(inv, "50000"));
        String id = (String) created.get("id");
        redFlushService.submit(id);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "同意红冲");
        RedFlushApply a = applyDao.selectById(id);
        assertEquals(RedFlushApply.ST_INFO_ISSUED, a.getStatus());
        assertEquals(RedFlushApply.FILLER_SELLER, a.getInfoSheetFiller(),
                "该客户无 CONFIRMED 发票 → 销方填开");
    }

    @Test
    void approvedBuyerConfirmedFiller() {
        SalesInvoice inv = seedInvoice("buyer", "2026-10-01");
        // 同客户存在已确认发票 → 购方填开
        jdbc.update("UPDATE erp_fin_sales_invoice SET STATUS = 'CONFIRMED' WHERE ID = ?",
                inv.getId());
        Map<String, Object> created = redFlushService.create(payload(inv, "50000"));
        String id = (String) created.get("id");
        redFlushService.submit(id);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "同意红冲");
        RedFlushApply a = applyDao.selectById(id);
        assertEquals(RedFlushApply.FILLER_BUYER, a.getInfoSheetFiller());
    }

    @Test
    void crossPeriodRequiresEvaluationOpinion() {
        SalesInvoice inv = seedInvoice("xop", "2026-05-18");
        Map<String, Object> created = redFlushService.create(payload(inv, "50000"));
        String id = (String) created.get("id");
        redFlushService.submit(id);
        loginRole("ROLE_FINANCE_MGR");
        // 意见不含「更正申报」→ 回调抛 422 → 审批整体回滚
        assertThrows(Exception.class, () -> approvalEngine.pass(activeTaskId(id), "同意"));
        RedFlushApply a = applyDao.selectById(id);
        assertEquals(RedFlushApply.ST_DRAFT, a.getStatus(), "评估缺失 → 回滚保持草稿");
        // 补充评估意见后通过
        approvalEngine.pass(activeTaskId(id), "同意，已评估无需更正申报（属期差异不影响当期抵扣）");
        a = applyDao.selectById(id);
        assertEquals(RedFlushApply.ST_INFO_ISSUED, a.getStatus());
        assertTrue(a.getCrossPeriodNote().contains("更正申报"));
    }

    // ================================================================
    // 3.1 编号门禁 + 3.2 开票全联动 + 3.3 红冲凭证
    // ================================================================

    private String approvedApply(SalesInvoice inv, String amount) {
        Map<String, Object> created = redFlushService.create(payload(inv, amount));
        String id = (String) created.get("id");
        redFlushService.submit(id);
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "同意红冲，评估无需更正申报");
        return id;
    }

    @Test
    void issueGateRequiresInfoSheetNo() {
        SalesInvoice inv = seedInvoice("gate", "2026-10-01");
        String id = approvedApply(inv, "30000");
        // 编号未录入 → 开票 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redFlushService.issue(id));
        assertTrue(ex.getMessage().contains("信息表编号未录入"), ex.getMessage());
        // 登记编号 → 可开
        redFlushService.recordInfoSheetNo(id, "XGB20990001");
        RedFlushApply a = applyDao.selectById(id);
        assertEquals("XGB20990001", a.getInfoSheetNo());
    }

    @Test
    void issueFullLinkage() {
        SalesInvoice inv = seedInvoice("link", "2026-10-01");
        String id = approvedApply(inv, "30000");
        redFlushService.recordInfoSheetNo(id, "XGB20990002");
        RedInvoice red = redFlushService.issue(id);

        assertNotNull(red.getRedNo());
        assertEquals("XGB20990002", red.getInfoSheetNo());
        RedFlushApply linked = applyDao.selectById(id);
        assertEquals(linked.getApplyNo(), red.getFlushApplyNo());
        // 原票累计红冲 + 余额同步
        SalesInvoice refreshed = invoiceDao.selectById(inv.getId());
        assertEquals(0, new BigDecimal("30000").compareTo(refreshed.getRedAmount()));
        assertFalse(SalesInvoice.ST_RED_FULL.equals(refreshed.getStatus()), "部分红冲不置全额态");
        // AR 红冲回匹配池
        ArInvoice ar = arDao.selectById(inv.getArId());
        assertEquals(0, new BigDecimal("30000").compareTo(ar.getRedAmount()));
        // 申请单终态
        RedFlushApply a = applyDao.selectById(id);
        assertEquals(RedFlushApply.ST_ISSUED, a.getStatus());
        assertNotNull(a.getVoucherId(), "红冲凭证已生成");
        // 凭证草稿：借 6001 + 借 222102 / 贷 1122
        var lines = jdbc.queryForList(
                "SELECT l.ACCOUNT_CODE, l.DIRECTION, l.AMOUNT FROM erp_fin_voucher_line l "
                        + "WHERE l.VOUCHER_ID = ? ORDER BY l.DIRECTION, l.ACCOUNT_CODE",
                a.getVoucherId());
        assertEquals(3, lines.size());
    }

    @Test
    void partialThenFullRedFlush() {
        SalesInvoice inv = seedInvoice("twice", "2026-10-01");
        String id1 = approvedApply(inv, "30000");
        redFlushService.recordInfoSheetNo(id1, "XGB-A");
        redFlushService.issue(id1);
        // 二次红冲 70000 → 全额
        String id2 = approvedApply(inv, "70000");
        redFlushService.recordInfoSheetNo(id2, "XGB-B");
        redFlushService.issue(id2);
        SalesInvoice refreshed = invoiceDao.selectById(inv.getId());
        assertEquals(SalesInvoice.ST_RED_FULL, refreshed.getStatus());
        // 第三次申请 → 阻断（C-4.14-12）
        Map<String, Object> p = payload(inv, "1000");
        assertThrows(ServiceException.class, () -> redFlushService.create(p));
    }

    @Test
    void duplicateInfoSheetRegistrationBlocked() {
        SalesInvoice inv = seedInvoice("dupno", "2026-10-01");
        String id = approvedApply(inv, "10000");
        redFlushService.recordInfoSheetNo(id, "XGB-DUP");
        // 已登记编号后再次登记（状态仍 INFO_ISSUED）→ 覆盖需重新登记语义：直接开票即可；
        // 非 INFO_ISSUED 态登记 → 422
        redFlushService.issue(id);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redFlushService.recordInfoSheetNo(id, "XGB-AGAIN"));
        assertTrue(ex.getMessage().contains("仅信息单已生成态"), ex.getMessage());
    }

    // ================================================================
    // 4.2 红冲后重开（BR-4.14-29：原申请复活 + 强制关联 + 不一致须说明）
    // ================================================================

    /** 造关联开票申请（红字终态），返回 applyId */
    private String seedInvoiceApply(SalesInvoice inv) {
        String applyId = "junit-rf-apply-" + inv.getId();
        jdbc.update("INSERT INTO erp_sd_invoice_apply (ID, APPLY_NO, SHIP_ID, CUSTOMER_ID, "
                + "CUSTOMER_NAME, APPLY_AMOUNT, STATUS, INVOICE_ID, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, ?, 100000, 'RED', ?, 'junit', '0', 0)",
                applyId, "JUNITIA-" + inv.getId(), "junit-ship-" + inv.getId(),
                inv.getCustomerId(), inv.getCustomerName(), inv.getId());
        return applyId;
    }

    @Test
    void reopenResetsApplyWithLinkage() {
        SalesInvoice inv = seedInvoice("reopen", "2026-10-01");
        String applyId = seedInvoiceApply(inv);
        String flushId = approvedApply(inv, "30000");
        redFlushService.recordInfoSheetNo(flushId, "XGB-REOPEN");
        redFlushService.issue(flushId);

        Map<String, Object> out = redFlushService.reopen(flushId, "税收分类编码误用，按正确编码重开");
        assertEquals(applyId, out.get("applyId"));
        // 原申请复活 + 强制关联
        var row = jdbc.queryForMap(
                "SELECT STATUS, RED_FLUSH_APPLY_NO, REOPEN_NOTE, INVOICE_ID FROM erp_sd_invoice_apply WHERE ID = ?",
                applyId);
        assertEquals("DRAFT", row.get("STATUS"));
        assertNotNull(row.get("RED_FLUSH_APPLY_NO"), "红冲单号强制关联不可清空");
        assertTrue(String.valueOf(row.get("REOPEN_NOTE")).contains("编码"));
        assertNull(row.get("INVOICE_ID"), "原开票锚清空待重开回写");
    }

    @Test
    void reopenRequiresNote() {
        SalesInvoice inv = seedInvoice("renote", "2026-10-01");
        String flushId = approvedApply(inv, "10000");
        redFlushService.recordInfoSheetNo(flushId, "XGB-N");
        redFlushService.issue(flushId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redFlushService.reopen(flushId, " "));
        assertTrue(ex.getMessage().contains("重开原因必填"), ex.getMessage());
    }

    @Test
    void reopenBeforeIssueBlocked() {
        SalesInvoice inv = seedInvoice("rpre", "2026-10-01");
        String flushId = approvedApply(inv, "10000");
        // 未开票（仍 INFO_ISSUED）→ 重开 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redFlushService.reopen(flushId, "原因"));
        assertTrue(ex.getMessage().contains("仅已开票"), ex.getMessage());
    }

    @Test
    void linkedApplyAmountMismatchRequiresNote() {
        // BR-4.14-29 一致性校验在 InvoiceServiceImpl.submit（此处直测 DAO 层联动语义：
        // 关联回写 + reopenApplyNo 锚）
        SalesInvoice inv = seedInvoice("anchor", "2026-10-01");
        String applyId = seedInvoiceApply(inv);
        String flushId = approvedApply(inv, "30000");
        redFlushService.recordInfoSheetNo(flushId, "XGB-ANCHOR");
        redFlushService.issue(flushId);
        redFlushService.reopen(flushId, "重开原因留痕");
        // 红冲单侧回写重开申请号锚
        RedFlushApply a = applyDao.selectById(flushId);
        assertEquals("JUNITIA-" + inv.getId(), a.getReopenApplyNo());
        assertNotNull(applyId);
    }

    // ================================================================
    // 5.1/5.2 发票作废（三条件 + 审批执行）
    // ================================================================

    @Autowired
    private com.erp.service.fin.RedVoidService redVoidService;

    /** 造当月开具、无交付、未确认的发票 */
    private SalesInvoice seedVoidable(String suffix) {
        SalesInvoice inv = seedInvoice(suffix, java.time.LocalDate.now().toString());
        return inv;
    }

    @Test
    void voidThreeConditionsPass() {
        SalesInvoice inv = seedVoidable("vok");
        Map<String, Object> out = redVoidService.apply(Map.of(
                "invoiceId", inv.getId(), "reason", "开票信息有误当月作废"));
        String id = (String) out.get("id");
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(id), "同意作废");
        SalesInvoice refreshed = invoiceDao.selectById(inv.getId());
        assertEquals(SalesInvoice.ST_VOID, refreshed.getStatus());
        var row = jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_invoice_void_apply WHERE ID = ?", String.class, id);
        assertEquals("VOIDED", row);
    }

    @Test
    void crossMonthVoidBlocked() {
        SalesInvoice inv = seedInvoice("vmonth", "2026-01-15");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redVoidService.apply(Map.of("invoiceId", inv.getId(), "reason", "跨月作废")));
        assertTrue(ex.getMessage().contains("已跨月"), ex.getMessage());
        assertTrue(ex.getMessage().contains("红冲"), "阻断并引导红冲");
    }

    @Test
    void deliveredVoidBlocked() {
        SalesInvoice inv = seedVoidable("vdeliv");
        jdbc.update("INSERT INTO erp_fin_invoice_delivery (ID, INVOICE_NO, SOURCE, SOURCE_ID, "
                + "METHOD, STATUS, SENT_AT, RETRY_COUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (CONCAT('junit-vd-', ?), ?, 'SALES', ?, 'EMAIL', 'SENT', NOW(), 0, 'junit', '0', 0)",
                inv.getId(), inv.getInvoiceNo(), inv.getId());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redVoidService.apply(Map.of("invoiceId", inv.getId(), "reason", "已交付作废")));
        assertTrue(ex.getMessage().contains("已发送交付记录"), ex.getMessage());
    }

    @Test
    void confirmedVoidBlocked() {
        SalesInvoice inv = seedVoidable("vconf");
        inv.setStatus(SalesInvoice.ST_CONFIRMED);
        invoiceDao.updateById(inv);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redVoidService.apply(Map.of("invoiceId", inv.getId(), "reason", "已确认作废")));
        assertTrue(ex.getMessage().contains("已确认"), ex.getMessage());
    }

    @Test
    void voidRejectedInvoiceUntouched() {
        SalesInvoice inv = seedVoidable("vrej");
        Map<String, Object> out = redVoidService.apply(Map.of(
                "invoiceId", inv.getId(), "reason", "待驳回作废"));
        String id = (String) out.get("id");
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.reject(activeTaskId(id), "原因不充分，驳回");
        SalesInvoice refreshed = invoiceDao.selectById(inv.getId());
        assertEquals(SalesInvoice.ST_ISSUED, refreshed.getStatus(), "驳回不动发票");
        var row = jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_invoice_void_apply WHERE ID = ?", String.class, id);
        assertEquals("REJECTED", row);
    }

    @Test
    void voidedInvoiceCannotRedFlush() {
        // BR-4.14-28 联动：已作废发票发起红冲 → 422
        SalesInvoice inv = seedVoidable("vlink");
        inv.setStatus(SalesInvoice.ST_VOID);
        invoiceDao.updateById(inv);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> redFlushService.create(payload(inv, "1000")));
        assertTrue(ex.getMessage().contains("已作废"), ex.getMessage());
    }

    // ================================================================
    // 6.1/6.2 进项税额转出（直接 + 分摊 + 超限）
    // ================================================================

    @Autowired
    private com.erp.service.fin.InputTaxTransferOutService transferOutService;

    /** 造 CERTIFIED 进项发票（period=yyyyMM，日期取该月 10 日） */
    private String seedCertifiedInput(String suffix, String taxAmount, String period) {
        String id = "junit-rf-in-" + suffix;
        String day = period.substring(0, 4) + "-" + period.substring(4, 6) + "-10";
        jdbc.update("INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, "
                + "CERT_DATE, CERT_PERIOD, DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'junit-sup-t', '转出供应商', ?, ?, ?, 'junit-po-t', 'JUNITPOT', "
                + "'MATCHED', 'CERTIFIED', ?, ?, 'Y', 0, 'junit', '0', 0)",
                id, "JUNITTIN-" + suffix, day, taxAmount, taxAmount, day, period);
        return id;
    }

    @Test
    void directTransferSuccess() {
        String id = seedCertifiedInput("d1", "13000", "209909");
        Map<String, Object> out = transferOutService.directTransfer(Map.of(
                "invoiceId", id, "amount", "10400", "reason", "集体福利"));
        assertEquals(0, new BigDecimal("10400").compareTo((BigDecimal) out.get("transferOutAmount")));
        assertNotNull(out.get("voucherId"));
        // 台账回写
        var row = jdbc.queryForMap(
                "SELECT TRANSFER_OUT_AMOUNT FROM erp_fin_input_invoice WHERE ID = ?", id);
        assertEquals(0, new BigDecimal("10400")
                .compareTo(new BigDecimal(String.valueOf(row.get("TRANSFER_OUT_AMOUNT")))));
        // 凭证：借 660299 / 贷 222103
        var lines = jdbc.queryForList(
                "SELECT ACCOUNT_CODE, DIRECTION FROM erp_fin_voucher_line WHERE VOUCHER_ID = ?",
                out.get("voucherId"));
        assertEquals(2, lines.size());
    }

    @Test
    void directTransferOverRemainingBlocked() {
        String id = seedCertifiedInput("d2", "13000", "209909");
        transferOutService.directTransfer(Map.of(
                "invoiceId", id, "amount", "10400", "reason", "集体福利"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> transferOutService.directTransfer(Map.of(
                        "invoiceId", id, "amount", "3000", "reason", "再次转出")));
        assertTrue(ex.getMessage().contains("剩余可转出税额 2600"), ex.getMessage());
    }

    @Test
    void uncertifiedCannotTransfer() {
        String id = "junit-rf-in-pend";
        jdbc.update("INSERT INTO erp_fin_input_invoice (ID, INVOICE_NO, SUPPLIER_ID, SUPPLIER_NAME, "
                + "INVOICE_DATE, AMOUNT, TAX_AMOUNT, PO_ID, PO_NO, MATCH_STATUS, CERT_STATUS, "
                + "DEDUCTIBLE, TRANSFER_OUT_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, 'JUNITTIN-P', 'junit-sup-t', '转出供应商', '2099-09-10', 100000, 13000, "
                + "'junit-po-t', 'JUNITPOT', 'MATCHED', 'PENDING_CERT', 'Y', 0, 'junit', '0', 0)",
                id);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> transferOutService.directTransfer(Map.of(
                        "invoiceId", id, "amount", "1000", "reason", "未认证转出")));
        assertTrue(ex.getMessage().contains("仅已认证发票可转出"), ex.getMessage());
    }

    @Test
    void apportionTransferPerFormula() {
        // 50000 × 124800/1000000 = 6240（spec 场景数字）
        seedCertifiedInput("a1", "30000", "209909");
        seedCertifiedInput("a2", "20000", "209909");
        Map<String, Object> out = transferOutService.apportionTransfer(Map.of(
                "totalInputTax", "50000", "exemptSales", "124800",
                "totalSales", "1000000", "reason", "无法划分转出", "period", "209909"));
        assertEquals(0, new BigDecimal("6240.00")
                .compareTo((BigDecimal) out.get("transferTotal")));
        assertEquals(2, out.get("invoiceCount"));
        // 逐票分摊合计 = 6240
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> detail = (List<Map<String, Object>>) out.get("detail");
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> row : detail) {
            sum = sum.add((BigDecimal) row.get("transferOut"));
        }
        assertEquals(0, new BigDecimal("6240.00").compareTo(sum), "逐票分摊合计 = 转出总额");
    }
}
