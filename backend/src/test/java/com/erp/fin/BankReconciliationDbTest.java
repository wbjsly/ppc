package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.entity.fin.BankReceipt;
import com.erp.entity.fin.CashDoc;
import com.erp.entity.fin.FinBankAccount;
import com.erp.service.fin.BankReconciliationService;
import com.erp.dao.fin.BankReceiptDao;
import com.erp.dao.fin.CashDocDao;
import com.erp.dao.fin.FinBankAccountDao;
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
 * 银行对账 DbTest（add-bank-revaluation：spec bank-reconciliation 2.2/2.3/2.4/3.1~3.4）。
 * 共享库隔离：BRK 前缀造数 + purge 兜底（三次变更先例）。
 */
@SpringBootTest
class BankReconciliationDbTest {

    private static final String ACCT = "BRK-ACCT-01";
    private static final String PERIOD = "209911";
    private static final LocalDate P_START = LocalDate.of(2099, 11, 1);
    private static final LocalDate P_END = LocalDate.of(2099, 11, 30);

    @Autowired
    private BankReconciliationService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FinBankAccountDao bankDao;
    @Autowired
    private CashDocDao docDao;
    @Autowired
    private BankReceiptDao receiptDao;
    @Autowired
    private com.erp.service.fin.FinPeriodService periodService;

    private String stmtId;

    @BeforeEach
    void setUp() {
        purge();
        // 账户：账面 100,000
        FinBankAccount a = new FinBankAccount();
        a.setId(ACCT);
        a.setAccountName("BRK 测试户");
        a.setAccountNo("BRK" + (System.nanoTime() % 10000000));
        a.setCurrency("CNY");
        a.setBalance(new BigDecimal("100000.00"));
        a.setFcBalance(new BigDecimal("100000.00"));
        a.setBookingRate(BigDecimal.ONE);
        a.setStatus(FinBankAccount.ST_ACTIVE);
        bankDao.insert(a);
        login("junit", "ROLE_FIN_MGR");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        // 冒烟段 23 残留（SMKBR 对账单会被结账⑧全期间扫描命中——共享库隔离，三次变更先例）
        jdbc.update("DELETE FROM erp_fin_bank_reconcile_item WHERE BANK_ACCOUNT_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'SMKBR%') t1)");
        jdbc.update("DELETE FROM erp_fin_bank_statement WHERE ACCOUNT_NO LIKE 'SMKBR%'");
        jdbc.update("DELETE FROM erp_fin_bank_statement WHERE BANK_ACCOUNT_ID = ?", ACCT);
        jdbc.update("DELETE FROM erp_fin_bank_reconcile_item WHERE BANK_ACCOUNT_ID = ?", ACCT);
        jdbc.update("DELETE FROM erp_fin_bank_receipt WHERE RECEIPT_NO LIKE 'BRK-%'");
        jdbc.update("DELETE FROM erp_fin_cash_doc WHERE DOC_NO LIKE 'BRK-%'");
        jdbc.update("DELETE FROM erp_fin_bank_account WHERE ID = ?", ACCT);
    }

    private static void login(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, role));
    }

    // ---------- 2.2 录入与自校验 ----------

    private Map<String, Object> payload(String opening, String closing) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bankAccountId", ACCT);
        m.put("period", PERIOD);
        m.put("stmtNo", "BRK-STMT-" + PERIOD);
        m.put("openingBalance", opening);
        m.put("closingBalance", closing);
        return m;
    }

    @Test
    void saveSelfCheckBalanced() {
        // 已匹配回单 收 20,000 → 期初 80,000 + 20,000 = 期末 100,000 勾稽平衡
        receipt("BRK-R1", "RECEIVE", "20000", "MATCHED");
        Map<String, Object> out = service.save(payload("80000", "100000"));
        assertEquals(Boolean.TRUE, out.get("selfCheckBalanced"), "勾稽平衡");
        stmtId = (String) out.get("id");
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT STATUS FROM erp_fin_bank_statement WHERE ID=?", String.class, stmtId));
    }

    @Test
    void saveSelfCheckGapAllowsSave() {
        receipt("BRK-R1", "RECEIVE", "20000", "MATCHED");
        Map<String, Object> out = service.save(payload("80000", "105000"));
        // 勾稽差 5,000 = 未达账项，允许保存
        assertEquals(Boolean.FALSE, out.get("selfCheckBalanced"));
        assertEquals(0, new BigDecimal("5000").compareTo(
                (BigDecimal) out.get("selfCheckGap")), "提示勾稽差 5,000");
        stmtId = (String) out.get("id");
    }

    @Test
    void duplicateAccountPeriodBlocked() {
        Map<String, Object> out = service.save(payload("100000", "100000"));
        stmtId = (String) out.get("id");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                service.save(payload("100000", "100000")));
        assertTrue(ex.getMessage().contains("已存在"), ex.getMessage());
    }

    // ---------- 2.3 确认状态机 ----------

    @Test
    void confirmAuditRevokeLifecycle() {
        stmtId = (String) service.save(payload("100000", "100000")).get("id"); // 无未达，平衡
        service.confirm(stmtId);
        assertEquals("CONFIRMED", status(stmtId));
        service.audit(stmtId);
        assertEquals("AUDITED", status(stmtId));
        // 撤销须主管 + 原因
        login("junit", "ROLE_FIN_MGR");
        service.revoke(stmtId, "发现新流水");
        assertEquals("DRAFT", status(stmtId));
        assertEquals("发现新流水", jdbc.queryForObject(
                "SELECT REVOKE_REASON FROM erp_fin_bank_statement WHERE ID=?", String.class, stmtId));
    }

    @Test
    void confirmBlockedWhenUnbalanced() {
        // 账面 100,000 vs 银行 90,000，无未达项 → 调节不平 → 确认阻断
        stmtId = (String) service.save(payload("100000", "90000")).get("id");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm(stmtId));
        assertTrue(ex.getMessage().contains("不等") || ex.getMessage().contains("排查"), ex.getMessage());
        assertEquals("DRAFT", status(stmtId));
    }

    @Test
    void nonManagerAuditBlocked() {
        stmtId = (String) service.save(payload("100000", "100000")).get("id");
        service.confirm(stmtId);
        login("junit", "ROLE_FIN_ASSET");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.audit(stmtId));
        assertEquals(403, ex.getCode(), "出纳无权审核");
    }

    @Test
    void confirmedDirectBackToDraft() {
        stmtId = (String) service.save(payload("100000", "100000")).get("id");
        service.confirm(stmtId);
        service.revoke(stmtId, null); // CONFIRMED 撤销无需主管
        assertEquals("DRAFT", status(stmtId));
    }

    // ---------- 2.4 D6 已审期间锁定 ----------

    @Test
    void auditedCoverBlocksMatchAndUnmatch() {
        stmtId = (String) service.save(payload("100000", "100000")).get("id");
        service.confirm(stmtId);
        service.audit(stmtId);
        // AUDITED 后新增回单+单据，人工匹配应被锁定守卫阻断
        BankReceipt r = receipt("BRK-R-LOCK", "RECEIVE", "5000", "UNMATCHED");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                service.assertNotAuditedCover(r.getBankAccountId(), r.getTradeDate(), "匹配"));
        assertTrue(ex.getMessage().contains("AUDITED"), ex.getMessage());
        // 撤销后放行
        service.revoke(stmtId, "放行测试");
        assertDoesNotThrow(() ->
                service.assertNotAuditedCover(r.getBankAccountId(), r.getTradeDate(), "匹配"));
    }

    // ---------- 3.1 四类分类与账龄 ----------

    @Test
    void fourTypesClassified() {
        receipt("BRK-R-UN", "RECEIVE", "10000", "UNMATCHED");           // ③
        receipt("BRK-R-PAY", "PAY", "4000", "UNMATCHED");               // ④
        cashDoc("BRK-D-IN", "IN", "8000");                              // ①
        cashDoc("BRK-D-OUT", "OUT", "30000");                           // ②
        Map<String, Object> out = service.outstandingItems(ACCT, PERIOD);
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> sums = (Map<String, BigDecimal>) out.get("sums");
        assertEquals(0, new BigDecimal("10000").compareTo(sums.get("BANK_RCPT_ENT_PEND")));
        assertEquals(0, new BigDecimal("4000").compareTo(sums.get("BANK_PAY_ENT_PEND")));
        assertEquals(0, new BigDecimal("8000").compareTo(sums.get("ENT_RCPT_BANK_PEND")));
        assertEquals(0, new BigDecimal("30000").compareTo(sums.get("ENT_PAY_BANK_PEND")));
    }

    @Test
    void agedOver90DaysFlagged() {
        // 交易日 2099-06-01（相对 2099-11 期间造数固定，账龄按当前日期算会为负——用 SQL 回拨 createDate 无意义，
        // 改用过去日期回单：tradeDate 早于今天 90 天以上）
        BankReceipt r = new BankReceipt();
        r.setId(uuid());
        r.setReceiptNo("BRK-R-AGED");
        r.setBankAccountId(ACCT);
        r.setTradeDate(LocalDate.now().minusDays(160));
        r.setDirection(BankReceipt.DIR_RECEIVE);
        r.setAmount(new BigDecimal("1500"));
        r.setSource("MANUAL");
        r.setMatchStatus(BankReceipt.ST_UNMATCHED);
        r.setCreateBy("junit");
        receiptDao.insert(r);
        // 期间范围用回单所在月
        String agedPeriod = LocalDate.now().minusDays(160).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        Map<String, Object> out = service.outstandingItems(ACCT, agedPeriod);
        assertEquals(1, out.get("agedCount"), "超 90 天红标计数");
    }

    // ---------- 3.2 差异率与 3.3 调节表 ----------

    @Test
    void diffRateOverTolerance() {
        // 银行期末 2,380,000 vs 账面 100,000（造数账户）→ 差异率巨大，应标超容差
        stmtId = (String) service.save(payload("2380000", "2380000")).get("id");
        assertEquals("1", jdbc.queryForObject(
                "SELECT OVER_TOLERANCE FROM erp_fin_bank_statement WHERE ID=?", String.class, stmtId));
    }

    @Test
    void reconcileTableBalanced() {
        // 规格场景：账面 2,350,000；银行期末 2,380,000；③银行已收企业未收 30,000 → 两侧 2,380,000
        jdbc.update("UPDATE erp_fin_bank_account SET BALANCE=2350000.00, FC_BALANCE=2350000.00 WHERE ID=?", ACCT);
        receipt("BRK-R-BAL", "RECEIVE", "30000", "UNMATCHED"); // ③
        stmtId = (String) service.save(payload("2350000", "2380000")).get("id");
        Map<String, Object> detail = service.detail(stmtId);
        @SuppressWarnings("unchecked")
        Map<String, Object> table = (Map<String, Object>) detail.get("reconcileTable");
        assertEquals(Boolean.TRUE, table.get("balanced"), "调节平衡：" + table);
        assertEquals(0, new BigDecimal("2380000").compareTo((BigDecimal) table.get("leftSide")));
        assertEquals(0, new BigDecimal("2380000").compareTo((BigDecimal) table.get("rightSide")));
        // 平衡后可确认
        assertDoesNotThrow(() -> service.confirm(stmtId));
    }

    @Test
    void confirmBlockedOnUnbalancedReconcile() {
        stmtId = (String) service.save(payload("100000", "120000")).get("id"); // 差 20,000 无未达解释
        ServiceException ex = assertThrows(ServiceException.class, () -> service.confirm(stmtId));
        assertTrue(ex.getMessage().contains("排查") || ex.getMessage().contains("不等"), ex.getMessage());
    }

    // ---------- 3.4 处理留痕 ----------

    @Test
    void handlingTrailRecords() {
        receipt("BRK-R-H", "RECEIVE", "6600", "UNMATCHED");
        Map<String, Object> mark = new LinkedHashMap<>();
        mark.put("bankAccountId", ACCT);
        mark.put("period", PERIOD);
        mark.put("itemType", "BANK_RCPT_ENT_PEND");
        mark.put("anchorType", "RECEIPT");
        mark.put("anchorId", "x");
        mark.put("anchorNo", "BRK-R-H");
        mark.put("amount", "6600");
        mark.put("tradeDate", P_START.toString());
        mark.put("status", "PROCESSING");
        mark.put("handlingNote", "客户直汇待补录");
        service.markHandling(mark);
        mark.put("status", "DONE");
        service.markHandling(mark);
        List<Map<String, Object>> records = service.handlingRecords(ACCT, PERIOD);
        assertEquals(2, records.size(), "追加式轨迹");
        assertEquals("DONE", records.get(0).get("status"), "最新在前");
    }

    // ---------- 4.4 结账第⑧项两分支 ----------

    @Test
    @SuppressWarnings("unchecked")
    void checklistBlockedByUnauditedStatement() {
        // 本期存在 DRAFT 对账单（ACTIVE 账户）→ ⑧ FAIL
        stmtId = (String) service.save(payload("100000", "100000")).get("id");
        Map<String, Object> check = periodService.checklist(PERIOD);
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> item = items.stream()
                .filter(i -> "pendingBankRecon".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.FALSE, item.get("ok"), "未审对账单阻断");
        assertTrue(String.valueOf(item.get("detail")).contains("未审核"), "列示未审清单");
    }

    @Test
    @SuppressWarnings("unchecked")
    void checklistPassesWithoutStatement() {
        // 本期无对账单录入（银行未出单）→ ⑧按需放行
        Map<String, Object> check = periodService.checklist(PERIOD);
        List<Map<String, Object>> items = (List<Map<String, Object>>) check.get("items");
        Map<String, Object> item = items.stream()
                .filter(i -> "pendingBankRecon".equals(i.get("key"))).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, item.get("ok"), "未录对账单放行");
        assertTrue(String.valueOf(item.get("detail")).contains("未录入"), "放行说明");
    }

    // ---------- helpers ----------

    private BankReceipt receipt(String no, String dir, String amount, String matchStatus) {
        BankReceipt r = new BankReceipt();
        r.setId(uuid());
        r.setReceiptNo(no);
        r.setBankAccountId(ACCT);
        // 期间内日期（AGED 用例例外）
        r.setTradeDate(LocalDate.of(2099, 11, 15));
        r.setDirection(dir);
        r.setAmount(new BigDecimal(amount));
        r.setSource("MANUAL");
        r.setMatchStatus(matchStatus);
        r.setCreateBy("junit");
        receiptDao.insert(r);
        return r;
    }

    private void cashDoc(String no, String dir, String amount) {
        CashDoc d = new CashDoc();
        d.setId(uuid());
        d.setDocNo(no);
        d.setDirection(dir);
        d.setPartyType("CUSTOMER");
        d.setPartyId("BRK-P");
        d.setPartyName("BRK 对方");
        d.setAmount(new BigDecimal(amount));
        d.setActualAmount(new BigDecimal(amount));
        d.setCurrency("CNY");
        d.setPayMethod("电汇");
        d.setBankAccountId(ACCT);
        d.setPayDate(LocalDate.of(2099, 11, 16));
        d.setStatus(CashDoc.ST_POSTED);
        d.setCreateBy("junit");
        docDao.insert(d);
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_fin_bank_statement WHERE ID=?", String.class, id);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
