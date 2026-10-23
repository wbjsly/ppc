package com.erp.fin;

import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmExchangeRateDao;
import com.erp.entity.mdm.MdmExchangeRate;
import com.erp.service.impl.fin.FxRateServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记账汇率取数单测（add-fx-revaluation，design D1）：
 * 命中区间返回中间价 / 缺失抛 L1（C-4.6-03）/ 基础币=报价币返回 1 不查库。
 */
class FxRateServiceUnitTest {

    private MdmExchangeRateDao dao;
    private FxRateServiceImpl svc;
    private final LocalDate day = LocalDate.of(2026, 10, 15);

    @BeforeEach
    void setUp() {
        dao = mock(MdmExchangeRateDao.class);
        svc = new FxRateServiceImpl(dao);
    }

    private MdmExchangeRate rate(String r) {
        MdmExchangeRate m = new MdmExchangeRate();
        m.setBaseCcy("USD");
        m.setQuoteCcy("CNY");
        m.setRateType("MIDDLE");
        m.setRate(new BigDecimal(r));
        return m;
    }

    @Test
    void hitReturnsMiddleRate() {
        when(dao.selectHit(eq("USD"), eq("CNY"), eq("MIDDLE"), eq(day))).thenReturn(rate("7.120000"));
        assertEquals(0, new BigDecimal("7.120000")
                .compareTo(svc.requireMiddleRate("USD", "CNY", day)));
    }

    @Test
    void missingThrowsL1() {
        when(dao.selectHit(eq("EUR"), eq("CNY"), eq("MIDDLE"), eq(day))).thenReturn(null);
        ServiceException e = assertThrows(ServiceException.class,
                () -> svc.requireMiddleRate("EUR", "CNY", day));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("汇率主数据缺失"), e.getMessage());
    }

    @Test
    void sameCurrencyReturnsOneWithoutDb() {
        assertEquals(0, BigDecimal.ONE.compareTo(svc.requireMiddleRate("CNY", "CNY", day)));
    }
}
