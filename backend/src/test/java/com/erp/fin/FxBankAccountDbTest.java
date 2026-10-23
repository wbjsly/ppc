package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.service.fin.BankAccountService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-fx-revaluation task 2.4：外币银行账户开户固化（spec bank-account MODIFIED）。
 * 开户固化记账汇率 / 缺汇率 L1 阻断 / CNY 三列同步。
 */
@SpringBootTest
class FxBankAccountDbTest {

    @Autowired
    private BankAccountService bankAccountService;
    @Autowired
    private FinBankAccountDao bankDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_bank_account WHERE ACCOUNT_NO LIKE 'FXBA-%'");
        jdbc.update("DELETE FROM erp_mdm_exchange_rate WHERE ID='fx-ba-usd'");
    }

    private void seedUsdRate() {
        // USD/CNY 中间价 7.12（2099 全年生效，沿用 ApManagementDbTest 造数口径）
        jdbc.update("INSERT IGNORE INTO erp_mdm_exchange_rate (ID,BASE_CCY,QUOTE_CCY,RATE_TYPE,"
                + "EFFECTIVE_DATE,EXPIRE_DATE,RATE,SOURCE_FILE_NO,CREATE_BY,DEL_FLAG,VER_NO) "
                + "VALUES ('fx-ba-usd','USD','CNY','MIDDLE','2099-01-01','2099-12-31',7.120000,"
                + "'央行公告BA','junit','0',0)");
    }

    private Map<String, Object> payload(String accountNo, String currency, Object balance) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("accountName", "FXBA 测试账户 " + currency);
        p.put("accountNo", accountNo);
        p.put("currency", currency);
        p.put("balance", balance);
        p.put("openDate", "2099-03-01");
        return p;
    }

    // ---------- 1. 外币开户固化 ----------

    @Test
    void usdAccountFixesBookingRateAndFcBalance() {
        seedUsdRate();
        Map<String, Object> out = bankAccountService.create(payload("FXBA-USD-01", "USD", 10000));
        assertEquals("USD", out.get("currency"), "币种 USD");
        assertEquals(0, new BigDecimal("7.120000").compareTo((BigDecimal) out.get("bookingRate")),
                "开户日中间价固化记账汇率");
        assertEquals(0, new BigDecimal("10000").compareTo((BigDecimal) out.get("fcBalance")),
                "期初外币余额写 FC_BALANCE");
        // BALANCE = 外币额 × 记账汇率（本位币 2 位）
        assertEquals(0, new BigDecimal("71200.00").compareTo((BigDecimal) out.get("balance")),
                "BALANCE=10000×7.12=71200.00");
        // 固化后改汇率主数据不影响已开户账户（历史事实不可篡改）
        jdbc.update("UPDATE erp_mdm_exchange_rate SET RATE=9.999999 WHERE ID='fx-ba-usd'");
        Map<String, Object> again = bankAccountService.detail((String) out.get("id"));
        assertEquals(0, new BigDecimal("7.120000").compareTo((BigDecimal) again.get("bookingRate")),
                "记账汇率不随后续汇率变动");
    }

    // ---------- 2. 缺汇率 L1 阻断 ----------

    @Test
    void usdAccountMissingRateBlocks() {
        // 无 JPY 生效汇率 → JPY 开户 422 阻断（C-4.6-03）
        ServiceException ex = assertThrows(ServiceException.class, () ->
                bankAccountService.create(payload("FXBA-JPY-01", "JPY", 100000)));
        assertTrue(ex.getMessage().contains("汇率"), "缺汇率阻断并提示维护汇率主数据：" + ex.getMessage());
        // 未落库
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_bank_account WHERE ACCOUNT_NO='FXBA-JPY-01'",
                Integer.class).intValue(), "阻断后不落库");
    }

    // ---------- 3. CNY 三列同步（不查汇率主数据）----------

    @Test
    void cnyAccountSyncsThreeColumns() {
        // 汇率主数据为空也应成功（CNY 不查库）
        Map<String, Object> out = bankAccountService.create(payload("FXBA-CNY-01", "CNY", 5000));
        assertEquals(0, new BigDecimal("5000").compareTo((BigDecimal) out.get("balance")),
                "BALANCE=期初余额");
        assertEquals(0, new BigDecimal("5000").compareTo((BigDecimal) out.get("fcBalance")),
                "CNY 账户 FC_BALANCE≡BALANCE");
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) out.get("bookingRate")),
                "CNY 账户 BOOKING_RATE≡1");
    }

    // ---------- 4. 账号唯一 ----------

    @Test
    void duplicateAccountNoBlocks() {
        bankAccountService.create(payload("FXBA-DUP-01", "CNY", 100));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                bankAccountService.create(payload("FXBA-DUP-01", "CNY", 200)));
        assertTrue(ex.getMessage().contains("已存在"), "账号唯一校验：" + ex.getMessage());
    }

    // ---------- 5. 外币账户余额双列维护（task 3.1）----------

    @Test
    void fcAccountDeductAndAddMaintainBothColumns() {
        seedUsdRate();
        Map<String, Object> out = bankAccountService.create(payload("FXBA-USD-02", "USD", 10000));
        String id = (String) out.get("id");

        // 扣减 5000 USD：FC_BALANCE 10000→5000，BALANCE 71200→35600（5000×7.12）
        assertEquals(1, bankDao.deductBalance(id, new BigDecimal("5000")), "扣减生效");
        Map<String, Object> d = bankAccountService.detail(id);
        assertEquals(0, new BigDecimal("5000").compareTo((BigDecimal) d.get("fcBalance")),
                "FC_BALANCE 扣减外币额");
        assertEquals(0, new BigDecimal("35600.00").compareTo((BigDecimal) d.get("balance")),
                "BALANCE 同步扣外币额×记账汇率");

        // 守卫：再扣 6000 USD 超 FC_BALANCE（5000）→ 0（不透支，BR-4.6-21 外币侧）
        assertEquals(0, bankDao.deductBalance(id, new BigDecimal("6000")), "外币余额不足返回 0");

        // 入账 1000 USD：双列回加
        assertEquals(1, bankDao.addBalance(id, new BigDecimal("1000")), "入账生效");
        Map<String, Object> d2 = bankAccountService.detail(id);
        assertEquals(0, new BigDecimal("6000").compareTo((BigDecimal) d2.get("fcBalance")),
                "FC_BALANCE 入账 1000");
        assertEquals(0, new BigDecimal("42720.00").compareTo((BigDecimal) d2.get("balance")),
                "BALANCE 同步入账 7120.00");
    }
}
