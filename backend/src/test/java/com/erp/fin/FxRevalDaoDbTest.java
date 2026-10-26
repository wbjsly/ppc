package com.erp.fin;

import com.erp.dao.fin.FxRevalDao;
import com.erp.entity.fin.FxReval;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-fx-revaluation task 4.1：重估台账实体/DAO 往返（spec fx-revaluation）。
 */
@SpringBootTest
class FxRevalDaoDbTest {

    @Autowired
    private FxRevalDao dao;

    @BeforeEach
    @AfterEach
    void purge() {
        dao.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FxReval>()
                .eq(FxReval::getPeriod, "209912"));
    }

    @Test
    void insertSelectRoundtrip() {
        FxReval r = new FxReval();
        r.setId("rvt-junit-1");
        r.setRevalNo("REVAL-209912-0001");
        r.setPeriod("209912");
        r.setItemType(FxReval.TYPE_AR);
        r.setItemId("ar-1");
        r.setItemNo("AR-2099-001");
        r.setPartyId("C1");
        r.setCurrency("USD");
        r.setFcAmount(new BigDecimal("10000.00"));
        r.setBookingRate(new BigDecimal("7.120000"));
        r.setPeriodRate(new BigDecimal("7.200000"));
        r.setDiffAmount(new BigDecimal("800.00"));
        dao.insert(r);

        FxReval back = dao.selectById("rvt-junit-1");
        assertEquals("REVAL-209912-0001", back.getRevalNo());
        assertEquals("209912", back.getPeriod());
        assertEquals(FxReval.TYPE_AR, back.getItemType());
        assertEquals(0, new BigDecimal("800.00").compareTo(back.getDiffAmount()));

        // 期间查询与推号
        assertEquals(1, dao.selectCountByPeriod("209912"));
        assertEquals(1, dao.selectMaxSeq("209912").intValue());
        List<FxReval> rows = dao.selectByPeriod("209912");
        assertEquals(1, rows.size());
    }
}
