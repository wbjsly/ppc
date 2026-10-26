package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.ExpenseTypeDao;
import com.erp.entity.fin.ExpenseType;
import com.erp.service.fin.ExpenseTypeService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 费用报销/预提待摊 DbTest（add-expense-reimbursement：spec expense-reimbursement 2.1 起）。
 * 共享库隔离：EXP 前缀造数 + purge 兜底；显式登录防 SecurityContext 跨类污染（四次变更先例）。
 */
@SpringBootTest
class ExpenseReimburseDbTest {

    @Autowired
    private ExpenseTypeService typeService;
    @Autowired
    private ExpenseTypeDao typeDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        login();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_expense_type WHERE TYPE_CODE LIKE 'EXP-%'");
    }

    private static void login() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_FIN_MGR"));
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ---------- 2.1 费用类型映射：新增映射 / 停用后不可引用 ----------

    @Test
    void saveNewMapping() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typeCode", "EXP-TRAVEL");
        payload.put("typeName", "Junit 差旅");
        payload.put("accountCode", "660202");
        Map<String, Object> out = typeService.save(payload);
        assertEquals("660202", out.get("accountCode"));
        assertEquals("ACTIVE", out.get("status"));
        // 重复编码 422
        ServiceException ex = assertThrows(ServiceException.class, () -> typeService.save(payload));
        assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
        // 非借方科目 422
        payload.put("typeCode", "EXP-BADCUR");
        payload.put("accountCode", "2241"); // CR 负债
        ServiceException ex2 = assertThrows(ServiceException.class, () -> typeService.save(payload));
        assertTrue(ex2.getMessage().contains("借方"), ex2.getMessage());
        // 科目不存在 422
        payload.put("typeCode", "EXP-NOACC");
        payload.put("accountCode", "999999");
        ServiceException ex3 = assertThrows(ServiceException.class, () -> typeService.save(payload));
        assertTrue(ex3.getMessage().contains("不存在"), ex3.getMessage());
    }

    @Test
    void disabledTypeNotReferencable() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("typeCode", "EXP-DISABLE");
        payload.put("typeName", "Junit 停用类型");
        payload.put("accountCode", "660202");
        Map<String, Object> out = typeService.save(payload);
        String id = (String) out.get("id");
        // 启用态可引用
        assertEquals("660202", typeService.resolveAccountCode("EXP-DISABLE"));
        // 停用后不可引用（FR-4.6-7-5 语义）
        typeService.updateStatus(id, "DISABLED");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                typeService.resolveAccountCode("EXP-DISABLE"));
        assertTrue(ex.getMessage().contains("停用"), ex.getMessage());
        // 不存在的类型 → 映射缺失 422
        ServiceException ex2 = assertThrows(ServiceException.class, () ->
                typeService.resolveAccountCode("EXP-NOPE"));
        assertTrue(ex2.getMessage().contains("补录"), ex2.getMessage());
        // 恢复启用后可再次引用
        typeService.updateStatus(id, "ACTIVE");
        assertEquals("660202", typeService.resolveAccountCode("EXP-DISABLE"));
    }

    // ---------- 2.2 借款申请：实时超期判定 → 节点链加签（C-4.6-14，三场景） ----------

    private static final String EMP = "junit-loan-emp";

    @Autowired
    private com.erp.service.fin.EmployeeLoanService loanService;
    @Autowired
    private com.erp.dao.fin.EmployeeLoanDao loanDao;

    private void purgeLoans() {
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE = 'LOAN') v)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE = 'LOAN'");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                + "WHERE i.BIZ_TYPE = 'EmployeeLoan' AND i.BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_employee_loan WHERE EMPLOYEE_ID = ?) t2)", EMP);
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'EmployeeLoan' AND BIZ_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_employee_loan WHERE EMPLOYEE_ID = ?) t)", EMP);
        jdbc.update("DELETE FROM erp_fin_employee_loan WHERE EMPLOYEE_ID = ?", EMP);
    }

    private Map<String, Object> loanPayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employeeId", EMP);
        m.put("purpose", "junit 出差借款");
        m.put("amount", "5000");
        m.put("expectedDate", "2099-12-31");
        return m;
    }

    /** 造一笔超期未核销借款（放款 100 天前，阈值 90） */
    private void seedOverdueLoan(String id, String balance) {
        jdbc.update("INSERT INTO erp_fin_employee_loan (ID, LOAN_NO, EMPLOYEE_ID, EMPLOYEE_NAME, PURPOSE, "
                + "AMOUNT, OUTSTANDING_BALANCE, STATUS, PAID_DATE, CREATE_BY, CREATE_DATE, VER_NO, DEL_FLAG) "
                + "VALUES (?, ?, ?, 'Junit 员工', 'junit 超期借款', ?, ?, 'PAID', DATE_SUB(CURDATE(), INTERVAL 100 DAY), "
                + "'junit', NOW(), 0, '0')",
                id, "LN-OD-" + id, EMP, balance, balance);
    }

    @Test
    void applyRegularSingleNodeWhenNoOverdue() {
        try {
            purgeLoans();
            Map<String, Object> out = loanService.apply(loanPayload());
            assertEquals(Boolean.FALSE, out.get("overdueDetected"), "无超期不加签");
            assertEquals(1, out.get("chainLength"), "常规部门主管单节点");
            // 审批链快照进审批标题留痕
            String title = jdbc.queryForObject(
                    "SELECT TITLE FROM erp_sys_approval WHERE BIZ_TYPE='EmployeeLoan' AND BIZ_ID=?",
                    String.class, out.get("id"));
            assertTrue(title.contains("无超期"), "判定快照留痕：" + title);
        } finally {
            purgeLoans();
        }
    }

    @Test
    void applyAddsFinanceSignerWhenOverdue() {
        try {
            purgeLoans();
            seedOverdueLoan("junit-od-1", "5000");
            Map<String, Object> out = loanService.apply(loanPayload());
            assertEquals(Boolean.TRUE, out.get("overdueDetected"), "存在超期未核销 → 检出");
            assertEquals(2, out.get("chainLength"), "加签财务主管双签");
            assertTrue(((Integer) out.get("overdueDays")) > 90, "超期天数>90");
            String title = jdbc.queryForObject(
                    "SELECT TITLE FROM erp_sys_approval WHERE BIZ_TYPE='EmployeeLoan' AND BIZ_ID=?",
                    String.class, out.get("id"));
            assertTrue(title.contains("加签财务主管"), "加签快照留痕：" + title);
        } finally {
            purgeLoans();
        }
    }

    @Test
    void applyNoSignAfterOverdueCleared() {
        try {
            purgeLoans();
            seedOverdueLoan("junit-od-2", "5000");
            // 还清后（余额 0 → 不构成未核销）→ 提交不再加签
            jdbc.update("UPDATE erp_fin_employee_loan SET OUTSTANDING_BALANCE = 0 WHERE ID = 'junit-od-2'");
            Map<String, Object> out = loanService.apply(loanPayload());
            assertEquals(Boolean.FALSE, out.get("overdueDetected"), "还清后不加签（判定实时化）");
            assertEquals(1, out.get("chainLength"));
        } finally {
            purgeLoans();
        }
    }

    // ---------- 2.3 借款审批回调：挂账凭证 + 状态回写 ----------

    @Autowired
    private com.erp.service.approval.ApprovalEngine approvalEngine;
    @Autowired
    private com.erp.service.fin.GlVoucherService voucherService;

    private String activeTaskId(String loanId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                        + "WHERE i.BIZ_ID = ? AND t.STATUS = 'ACTIVE' LIMIT 1",
                String.class, loanId);
    }

    private static void loginRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, role));
    }

    @Test
    void loanApprovedPostsVoucherAndBalance() {
        try {
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
            Map<String, Object> out = loanService.apply(loanPayload());
            String loanId = (String) out.get("id");
            // 部门主管节点通过 → （无超期仅一节点）审批完成触发 Callback
            loginRole("ROLE_DEPT_HEAD");
            approvalEngine.pass(activeTaskId(loanId), "同意借款");
            // 状态与余额回写
            var loan = loanDao.selectById(loanId);
            assertEquals("PAID", loan.getStatus(), "审批通过 → 已放款");
            assertEquals(0, new BigDecimal("5000").compareTo(loan.getOutstandingBalance()), "余额=借款额");
            assertNotNull(loan.getPaidDate(), "放款日期（账龄起算）");
            assertNotNull(loan.getVoucherId(), "挂账凭证已生成");
            // 凭证行校验：借 1299 / 贷 1002
            java.util.List<java.util.Map<String, Object>> lines = jdbc.queryForList(
                    "SELECT ACCOUNT_CODE, DIRECTION, AMOUNT FROM erp_fin_voucher_line "
                            + "WHERE VOUCHER_ID = ? ORDER BY DIRECTION DESC", loan.getVoucherId());
            assertEquals(2, lines.size(), "两行分录");
            assertEquals("1299", lines.get(0).get("ACCOUNT_CODE"), "借方 1299");
            assertEquals("1002", lines.get(1).get("ACCOUNT_CODE"), "贷方 1002");
        } finally {
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    @Test
    void loanRejectedLeavesNoVoucher() {
        try {
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
            Map<String, Object> out = loanService.apply(loanPayload());
            String loanId = (String) out.get("id");
            loginRole("ROLE_DEPT_HEAD");
            approvalEngine.reject(activeTaskId(loanId), "事由不充分退回");
            var loan = loanDao.selectById(loanId);
            assertEquals("REJECTED", loan.getStatus(), "驳回 → REJECTED");
            assertNull(loan.getVoucherId(), "驳回无凭证");
            assertEquals(0, new BigDecimal("0").compareTo(loan.getOutstandingBalance()));
        } finally {
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    // ---------- 2.4 账龄监控视图（BR-4.6-02 提示性红标） ----------

    /** 造放款 N 天前的未核销借款 */
    private void seedAgedLoan(String id, String balance, int daysAgo) {
        jdbc.update("INSERT INTO erp_fin_employee_loan (ID, LOAN_NO, EMPLOYEE_ID, EMPLOYEE_NAME, PURPOSE, "
                + "AMOUNT, OUTSTANDING_BALANCE, STATUS, PAID_DATE, CREATE_BY, CREATE_DATE, VER_NO, DEL_FLAG) "
                + "VALUES (?, ?, ?, 'Junit 员工', 'junit 账龄借款', ?, ?, 'PAID', "
                + "DATE_SUB(CURDATE(), INTERVAL ? DAY), 'junit', NOW(), 0, '0')",
                id, "LN-AGE-" + id, EMP, balance, balance, daysAgo);
    }

    private Map<String, Object> viewRow() {
        return loanService.agingView().stream()
                .filter(m -> EMP.equals(m.get("employeeId")))
                .findFirst().orElse(null);
    }

    @Test
    void agingViewFlagsOverdueRed() {
        try {
            purgeLoans();
            seedAgedLoan("junit-age-od", "8000", 100); // 100 天 > 90 阈值
            Map<String, Object> row = viewRow();
            assertNotNull(row, "视图含该员工");
            assertEquals(Boolean.TRUE, row.get("overdue"), "超期红标（需财务主管处理）");
            assertEquals(1, row.get("loanCount"));
            assertEquals(0, new BigDecimal("8000").compareTo((BigDecimal) row.get("outstandingTotal")));
            assertTrue(((Integer) row.get("maxAgingDays")) > 90, "最大账龄>90");
        } finally {
            purgeLoans();
        }
    }

    @Test
    void agingViewNormalNoFlag() {
        try {
            purgeLoans();
            seedAgedLoan("junit-age-ok", "3000", 10); // 10 天 < 90 阈值
            Map<String, Object> row = viewRow();
            assertNotNull(row, "视图含该员工");
            assertEquals(Boolean.FALSE, row.get("overdue"), "未超期不标红");
            assertEquals(10, row.get("maxAgingDays"));
        } finally {
            purgeLoans();
        }
    }

    // ================================================================
    // 组 3：报销闭环（3.1 提交校验 / 3.2 审批落账 FIFO / 3.3 结算 / 3.4 两节点）
    // ================================================================

    @Autowired
    private com.erp.service.fin.ExpenseReimburseService reimbService;
    @Autowired
    private com.erp.dao.fin.ReimburseDao reimburseDao;
    @Autowired
    private com.erp.dao.fin.LoanWriteoffDao writeoffDao;

    private void purgeReimb() {
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_voucher "
                + "WHERE SOURCE_TYPE IN ('REIMB','LWO','LOANREF')) v)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('REIMB','LWO','LOANREF')");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID = i.ID "
                + "WHERE i.BIZ_TYPE = 'ExpenseReimburse'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'ExpenseReimburse'");
        jdbc.update("DELETE FROM erp_fin_cash_doc WHERE REQ_NO LIKE 'EXP%' OR DOC_NO LIKE 'RF%'");
        jdbc.update("DELETE FROM erp_fin_loan_writeoff WHERE EMPLOYEE_ID = ?", EMP);
        jdbc.update("DELETE FROM erp_fin_reimburse_line WHERE REIMBURSE_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_reimburse WHERE EMPLOYEE_ID = ?) t)", EMP);
        jdbc.update("DELETE FROM erp_fin_reimburse WHERE EMPLOYEE_ID = ?", EMP);
        jdbc.update("DELETE FROM erp_fin_bank_account WHERE ID = 'junit-bank-exp'");
    }

    /** 出纳结算用银行账户（余额 100,000） */
    private void seedBank(String id, String balance) {
        jdbc.update("INSERT INTO erp_fin_bank_account (ID, ACCOUNT_NAME, ACCOUNT_NO, CURRENCY, "
                + "BALANCE, FC_BALANCE, BOOKING_RATE, STATUS, CREATE_BY, CREATE_DATE, VER_NO, DEL_FLAG) "
                + "VALUES (?, 'Junit 结算户', ?, 'CNY', ?, ?, 1, 'ACTIVE', 'junit', NOW(), 0, '0')",
                id, "JUNITEXP" + (System.nanoTime() % 10000000), balance, balance);
    }

    private Map<String, Object> line(String type, String amount, String invoiceNo) {
        Map<String, Object> ln = new LinkedHashMap<>();
        ln.put("expenseType", type);
        ln.put("amount", amount);
        ln.put("occurDate", LocalDate.now().toString());
        ln.put("invoiceNo", invoiceNo);
        ln.put("invoiceVendor", "Junit 销方");
        return ln;
    }

    private Map<String, Object> reimbPayload(String total, Map<String, Object>... lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("employeeId", EMP);
        m.put("totalAmount", total);
        m.put("lines", List.of(lines));
        return m;
    }

    /** 两节点审批通过：部门主管 → 财务审核 */
    private void approve(String expId) {
        loginRole("ROLE_DEPT_HEAD");
        approvalEngine.pass(activeTaskId(expId), "部门同意");
        loginRole("ROLE_FINANCE_MGR");
        approvalEngine.pass(activeTaskId(expId), "票据合规审核通过");
        loginRole("ROLE_FIN_MGR");
    }

    // ---------- 3.1 提交校验（三场景） ----------

    @Test
    void submitRejectsDuplicateInvoiceShowingOriginalNo() {
        try {
            purgeReimb();
            Map<String, Object> first = reimbService.submit(
                    reimbPayload("100", line("TRAVEL", "100", "INV-DUP-001")));
            String firstNo = (String) first.get("expNo");
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    reimbService.submit(reimbPayload("100", line("TRAVEL", "100", "INV-DUP-001"))));
            assertTrue(ex.getMessage().contains("历史报销"), ex.getMessage());
            assertTrue(ex.getMessage().contains(firstNo), "展示原单号：" + ex.getMessage());
        } finally {
            purgeReimb();
        }
    }

    @Test
    void submitAcceptsNewInvoice() {
        try {
            purgeReimb();
            Map<String, Object> out = reimbService.submit(
                    reimbPayload("300", line("TRAVEL", "300", "INV-NEW-001")));
            assertNotNull(out.get("expNo"));
            var r = reimburseDao.selectById((String) out.get("id"));
            assertEquals("PENDING", r.getStatus(), "提交 → 审批中");
            assertEquals(0, new BigDecimal("300").compareTo(r.getTotalAmount()));
            // 单据内同发票两行 → 422
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    reimbService.submit(reimbPayload("200",
                            line("TRAVEL", "100", "INV-INTRA-001"),
                            line("OFFICE", "100", "INV-INTRA-001"))));
            assertTrue(ex.getMessage().contains("重复"), ex.getMessage());
        } finally {
            purgeReimb();
        }
    }

    @Test
    void submitBlockedWhenMappingMissing() {
        try {
            purgeReimb();
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    reimbService.submit(reimbPayload("100", line("EXP-NOEXIST", "100", "INV-NO-MAP"))));
            assertTrue(ex.getMessage().contains("补录"), ex.getMessage());
            // 行合计 ≠ 总额 → 422
            ServiceException ex2 = assertThrows(ServiceException.class, () ->
                    reimbService.submit(reimbPayload("999", line("TRAVEL", "100", "INV-SUM-CHK"))));
            assertTrue(ex2.getMessage().contains("不一致"), ex2.getMessage());
        } finally {
            purgeReimb();
        }
    }

    // ---------- 3.2 审批落账（四场景：张三典型 / FIFO 跨两笔 / BR-4.6-01 全额 / 无借款仅挂应付） ----------

    @Test
    void typicalScenarioVouchersAndWriteoff() {
        try {
            purgeReimb();
            purgeLoans();
            seedAgedLoan("junit-zs-loan", "10000", 30); // 张三借款 10,000
            Map<String, Object> out = reimbService.submit(reimbPayload("12400",
                    line("TRAVEL", "10000", "INV-ZS-001"),
                    line("MEETING", "2400", "INV-ZS-002")));
            String expId = (String) out.get("id");
            approve(expId);
            // 报销单状态与结算态
            var r = reimburseDao.selectById(expId);
            assertEquals("APPROVED", r.getStatus());
            assertEquals("PENDING", r.getSettleStatus(), "差额 2,400 待出纳补付");
            assertNotNull(r.getVoucherId());
            // 凭证① 报销凭证：借 660202 10000 + 660203 2400 / 贷 2241 12400（三行）
            java.util.List<java.util.Map<String, Object>> v1 = jdbc.queryForList(
                    "SELECT ACCOUNT_CODE, DIRECTION, AMOUNT FROM erp_fin_voucher_line "
                            + "WHERE VOUCHER_ID = ? ORDER BY FIELD(DIRECTION,'DR','CR'), ACCOUNT_CODE",
                    r.getVoucherId());
            assertEquals(3, v1.size(), "报销凭证三行");
            assertEquals("2241", v1.get(2).get("ACCOUNT_CODE"), "贷方 2241");
            // 冲销：借款 10,000 清零 + 冲销明细 + 冲销凭证
            assertEquals(1, writeoffDao.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                    .LambdaQueryWrapper<com.erp.entity.fin.LoanWriteoff>()
                    .eq(com.erp.entity.fin.LoanWriteoff::getReimburseId, expId)));
            var loan = loanDao.selectById("junit-zs-loan");
            assertEquals(0, BigDecimal.ZERO.compareTo(loan.getOutstandingBalance()), "借款清零");
            Integer lwoVouchers = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='REIMB' "
                            + "AND VOUCHER_TYPE='LWO' AND SOURCE_DOC_NO=?", Integer.class, r.getExpNo());
            assertEquals(1, lwoVouchers, "冲销凭证已生成");
            // 冲销凭证行：借 2241 10000 / 贷 1299 10000
            java.util.List<java.util.Map<String, Object>> v2 = jdbc.queryForList(
                    "SELECT ACCOUNT_CODE, DIRECTION, AMOUNT FROM erp_fin_voucher_line l "
                            + "JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID "
                            + "WHERE v.VOUCHER_TYPE='LWO' AND v.SOURCE_DOC_NO=? ORDER BY DIRECTION DESC",
                    r.getExpNo());
            assertEquals(2, v2.size());
            assertEquals("1299", v2.get(1).get("ACCOUNT_CODE"), "贷方 1299");
        } finally {
            purgeReimb();
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    @Test
    void fifoAcrossTwoLoans() {
        try {
            purgeReimb();
            purgeLoans();
            seedAgedLoan("junit-fifo-1", "5000", 30); // 更早
            seedAgedLoan("junit-fifo-2", "6000", 20);
            Map<String, Object> out = reimbService.submit(reimbPayload("9000", line("TRAVEL", "9000", "INV-FIFO-001")));
            approve((String) out.get("id"));
            // FIFO：先冲 5000 全额，再冲 6000 中 4000 → 第二笔余额 2000
            assertEquals(0, loanDao.selectById("junit-fifo-1").getOutstandingBalance()
                    .compareTo(BigDecimal.ZERO), "第一笔清零");
            assertEquals(0, new BigDecimal("2000").compareTo(loanDao.selectById("junit-fifo-2").getOutstandingBalance()), "第二笔剩 2000");
            assertEquals(2, writeoffDao.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                    .LambdaQueryWrapper<com.erp.entity.fin.LoanWriteoff>()
                    .eq(com.erp.entity.fin.LoanWriteoff::getReimburseId, (String) out.get("id"))));
        } finally {
            purgeReimb();
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    @Test
    void br4601FullWriteoffNoCashPayment() {
        try {
            purgeReimb();
            purgeLoans();
            seedAgedLoan("junit-br1-loan", "10000", 30);
            Map<String, Object> out = reimbService.submit(reimbPayload("8000", line("TRAVEL", "8000", "INV-BR1-001")));
            String expId = (String) out.get("id");
            approve(expId);
            // 报销 8000 ≤ 借款 10000 → 全额冲销，差额 0 → DONE、零现金单据（BR-4.6-01 不得付现）
            var r = reimburseDao.selectById(expId);
            assertEquals("DONE", r.getSettleStatus(), "差额 0 闭环");
            assertEquals(0, new BigDecimal("2000").compareTo(loanDao.selectById("junit-br1-loan").getOutstandingBalance()));
            Integer docs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_fin_cash_doc WHERE REQ_NO = ?", Integer.class, r.getExpNo());
            assertEquals(0, docs, "无任何付款单据（不得直接支付现金）");
        } finally {
            purgeReimb();
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    @Test
    void noLoanOnlyPayableVoucher() {
        try {
            purgeReimb();
            purgeLoans();
            Map<String, Object> out = reimbService.submit(reimbPayload("500", line("OFFICE", "500", "INV-NOL-001")));
            String expId = (String) out.get("id");
            approve(expId);
            var r = reimburseDao.selectById(expId);
            assertEquals(0, writeoffDao.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                    .LambdaQueryWrapper<com.erp.entity.fin.LoanWriteoff>()
                    .eq(com.erp.entity.fin.LoanWriteoff::getReimburseId, expId)), "无冲销明细");
            assertEquals("PENDING", r.getSettleStatus(), "全额待补付");
            Integer lwo = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_fin_voucher WHERE VOUCHER_TYPE='LWO'", Integer.class);
            assertEquals(0, lwo, "无冲销凭证");
        } finally {
            purgeReimb();
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    // ---------- 3.3 差额结算（三场景） ----------

    @Test
    void payDifferenceClosesLoop() {
        try {
            purgeReimb();
            purgeLoans();
            seedAgedLoan("junit-pd-loan", "10000", 30);
            seedBank("junit-bank-exp", "100000");
            Map<String, Object> out = reimbService.submit(reimbPayload("12400",
                    line("TRAVEL", "10000", "INV-PD-001"), line("MEETING", "2400", "INV-PD-002")));
            String expId = (String) out.get("id");
            approve(expId);
            // 差额 2,400 补付
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("bankAccountId", "junit-bank-exp");
            payload.put("payDate", LocalDate.now().toString());
            Map<String, Object> pay = reimbService.payDifference(expId, payload);
            assertEquals(0, new BigDecimal("2400").compareTo((BigDecimal) pay.get("difference")));
            // 余额条件扣减 100000 → 97600
            assertEquals(0, new BigDecimal("97600").compareTo(jdbc.queryForObject(
                    "SELECT BALANCE FROM erp_fin_bank_account WHERE ID='junit-bank-exp'", BigDecimal.class)));
            // 收付单据 + 凭证 借2241/贷1002
            var doc = jdbc.queryForMap("SELECT STATUS, PAY_TYPE FROM erp_fin_cash_doc WHERE REQ_NO = ?",
                    ((Map<?, ?>) pay).get("docNo") == null ? "" : reimburseDao.selectById(expId).getExpNo());
            assertEquals("POSTED", doc.get("STATUS"));
            assertEquals("REIMBURSE", doc.get("PAY_TYPE"));
            assertEquals("DONE", reimburseDao.selectById(expId).getSettleStatus(), "结算闭环");
            // 重复补付 422
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    reimbService.payDifference(expId, payload));
            assertTrue(ex.getMessage().contains("已结算") || ex.getMessage().contains("无待补"), ex.getMessage());
        } finally {
            purgeReimb();
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    @Test
    void payDifferenceInsufficientBalanceBlocks() {
        try {
            purgeReimb();
            purgeLoans();
            seedAgedLoan("junit-pb-loan", "10000", 30);
            seedBank("junit-bank-exp", "100"); // 余额 100 < 差额 2400
            Map<String, Object> out = reimbService.submit(reimbPayload("12400",
                    line("TRAVEL", "10000", "INV-PB-001"), line("MEETING", "2400", "INV-PB-002")));
            String expId = (String) out.get("id");
            approve(expId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("bankAccountId", "junit-bank-exp");
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    reimbService.payDifference(expId, payload));
            assertTrue(ex.getMessage().contains("余额不足"), ex.getMessage());
            // 无单据落库、余额不变
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_fin_cash_doc WHERE REQ_NO = ?",
                    Integer.class, reimburseDao.selectById(expId).getExpNo()), "阻断后无单据");
            assertEquals(0, new BigDecimal("100").compareTo(jdbc.queryForObject(
                    "SELECT BALANCE FROM erp_fin_bank_account WHERE ID='junit-bank-exp'", BigDecimal.class)));
        } finally {
            purgeReimb();
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    @Test
    void refundClearsLoanBalance() {
        try {
            purgeReimb();
            purgeLoans();
            seedBank("junit-bank-exp", "50000");
            // 先审批（无借款 → 不触发 FIFO 冲销），再造剩余借款——避免审批时自动冲掉 100
            Map<String, Object> out = reimbService.submit(reimbPayload("100", line("TRAFFIC", "100", "INV-RF-001")));
            String expId = (String) out.get("id");
            approve(expId);
            seedAgedLoan("junit-rf-loan", "2000", 5); // 剩余借款 2000
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("loanId", "junit-rf-loan");
            payload.put("amount", "2000");
            payload.put("bankAccountId", "junit-bank-exp");
            payload.put("payDate", LocalDate.now().toString());
            Map<String, Object> ref = reimbService.refund(expId, payload);
            assertEquals(0, new BigDecimal("0").compareTo(
                    loanDao.selectById("junit-rf-loan").getOutstandingBalance()), "借款清零");
            // 收款入账 50000 → 52000 + 凭证 借1002/贷1299
            assertEquals(0, new BigDecimal("52000").compareTo(jdbc.queryForObject(
                    "SELECT BALANCE FROM erp_fin_bank_account WHERE ID='junit-bank-exp'", BigDecimal.class)));
            java.util.List<java.util.Map<String, Object>> lines = jdbc.queryForList(
                    "SELECT l.ACCOUNT_CODE, l.DIRECTION FROM erp_fin_voucher_line l "
                            + "JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                            + "WHERE v.VOUCHER_TYPE='LOANREF' ORDER BY FIELD(l.DIRECTION,'CR','DR')");
            assertEquals("1299", lines.get(0).get("ACCOUNT_CODE"), "贷方 1299");
            // 再退（余额已 0）→ 422 阻断
            ServiceException ex = assertThrows(ServiceException.class, () ->
                    reimbService.refund(expId, payload));
            assertTrue(ex.getMessage().contains("超过") || ex.getMessage().contains("余额"), ex.getMessage());
        } finally {
            purgeReimb();
            purgeLoans();
            loginRole("ROLE_FIN_MGR");
        }
    }

    // ---------- 3.4 两节点审批（两场景） ----------

    @Test
    void twoNodesSequentialFlow() {
        try {
            purgeReimb();
            Map<String, Object> out = reimbService.submit(reimbPayload("600", line("TEL", "600", "INV-2N-001")));
            String expId = (String) out.get("id");
            // 节点1 部门主管通过 → 仍 PENDING、无凭证
            loginRole("ROLE_DEPT_HEAD");
            approvalEngine.pass(activeTaskId(expId), "部门同意");
            assertEquals("PENDING", reimburseDao.selectById(expId).getStatus(), "财务节点未过仍审批中");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='REIMB'", Integer.class),
                    "未完成审批无凭证");
            // 节点2 财务审核通过 → APPROVED + 凭证落库
            loginRole("ROLE_FINANCE_MGR");
            approvalEngine.pass(activeTaskId(expId), "票据合规");
            assertEquals("APPROVED", reimburseDao.selectById(expId).getStatus());
            assertTrue(reimburseDao.selectById(expId).getVoucherId() != null);
        } finally {
            purgeReimb();
            loginRole("ROLE_FIN_MGR");
        }
    }

    @Test
    void rejectionLeavesNoVoucher() {
        try {
            purgeReimb();
            Map<String, Object> out = reimbService.submit(reimbPayload("700", line("TRAVEL", "700", "INV-RJ-001")));
            String expId = (String) out.get("id");
            loginRole("ROLE_DEPT_HEAD");
            approvalEngine.reject(activeTaskId(expId), "预算不足退回修改");
            assertEquals("REJECTED", reimburseDao.selectById(expId).getStatus());
            assertNull(reimburseDao.selectById(expId).getVoucherId(), "驳回无凭证");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='REIMB'", Integer.class));
        } finally {
            purgeReimb();
            loginRole("ROLE_FIN_MGR");
        }
    }
}
