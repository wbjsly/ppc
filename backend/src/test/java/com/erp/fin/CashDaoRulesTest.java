package com.erp.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.entity.fin.FinBankAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 收付中心 DAO 侧条件更新语义（spec bank-account ADDED 余额条件入账）：
 * addBalance 仅 ACTIVE 账户生效；停用/不存在返回 0（422 由服务层处理）。
 */
@SpringBootTest
class CashDaoRulesTest {

    @Autowired
    private FinBankAccountDao bankDao;

    private String accountId;

    @BeforeEach
    void freshIds() {
        accountId = "bank-junit-cash" + System.nanoTime();
        FinBankAccount a = new FinBankAccount();
        a.setId(accountId);
        a.setAccountName("单元测试户");
        a.setAccountNo("6222" + System.nanoTime() % 1000000000L);
        a.setCurrency("CNY");
        a.setBalance(new BigDecimal("1000.00"));
        // add-fx-revaluation 三列模型：CNY 账户 FC_BALANCE≡BALANCE、BOOKING_RATE≡1
        a.setFcBalance(new BigDecimal("1000.00"));
        a.setBookingRate(java.math.BigDecimal.ONE);
        a.setStatus(FinBankAccount.ST_ACTIVE);
        bankDao.insert(a);
    }

    @AfterEach
    void cleanup() {
        bankDao.deleteById(accountId);
    }

    @Test
    void addBalanceActiveAccount() {
        int rows = bankDao.addBalance(accountId, new BigDecimal("500.00"));
        assertEquals(1, rows);
        FinBankAccount after = bankDao.selectById(accountId);
        assertEquals(0, new BigDecimal("1500.00").compareTo(after.getBalance()));
    }

    @Test
    void addBalanceDisabledAccountRejected() {
        bankDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FinBankAccount>()
                .eq(FinBankAccount::getId, accountId)
                .set(FinBankAccount::getStatus, "DISABLED"));
        int rows = bankDao.addBalance(accountId, new BigDecimal("500.00"));
        assertEquals(0, rows);
        FinBankAccount after = bankDao.selectById(accountId);
        assertEquals(0, new BigDecimal("1000.00").compareTo(after.getBalance()));
    }

    @Test
    void addBalanceMissingAccountZero() {
        int rows = bankDao.addBalance("no-such-account" + System.nanoTime(), BigDecimal.ONE);
        assertEquals(0, rows);
    }
}
