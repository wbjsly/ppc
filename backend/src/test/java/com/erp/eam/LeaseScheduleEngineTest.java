package com.erp.eam;

import com.erp.eam.LeaseScheduleEngine.ScheduleRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-asset-transfer-lease：摊余成本计划引擎纯单测（design D5）。
 * PV 精确值、实际利率法逐月递推、末月轧差清零、重计量剩余行。
 *
 * <p>PV 锚说明（用户已确认口径 A）：S-4.13-12 场景「3 年季付 ¥45,000、利率 4.5%」
 * 规格叙事值 ¥486,000 疑为"约九折"估算（540,000×0.9），非常规折现可推导；
 * 按 design D5 口径（按月折现、付款落各期末）数学精确 PV = ¥502,367.58，
 * 单测以公式精确值为准，486,000 记为规格叙事偏差（~3.4%）。
 */
class LeaseScheduleEngineTest {

    private static final BigDecimal RATE_45 = new BigDecimal("0.045");

    @Test
    void presentValueQuarterly3Years() {
        // 3 年季付 45,000：12 期付款落在第 3,6,...,36 月末，月利率 0.375%
        BigDecimal pv = LeaseScheduleEngine.presentValue(new BigDecimal("45000"),
                3, 36, RATE_45);
        assertEquals(0, new BigDecimal("502367.58").compareTo(pv),
                "PV = Σ 45000/(1+0.045/12)^(3k) = 502367.58（口径 A：公式精确值）");
    }

    @Test
    void presentValueZeroRateIsSimpleSum() {
        // 零利率：PV = 付款 × 期数
        BigDecimal pv = LeaseScheduleEngine.presentValue(new BigDecimal("1000"),
                1, 12, BigDecimal.ZERO);
        assertEquals(0, new BigDecimal("12000.00").compareTo(pv));
    }

    @Test
    void generateRowCountsAndT0Row() {
        List<ScheduleRow> rows = LeaseScheduleEngine.generate(
                new BigDecimal("100000"), new BigDecimal("10000"),
                3, 12, BigDecimal.ZERO, "202601");
        assertEquals(13, rows.size(), "T0 + 12 月 = 13 行");
        ScheduleRow t0 = rows.get(0);
        assertEquals(0, t0.seq());
        assertEquals("202601", t0.periodTag());
        assertEquals(0, new BigDecimal("100000.00").compareTo(t0.endBal()), "T0 期末 = PV");
        assertEquals(0, t0.interest().compareTo(BigDecimal.ZERO), "T0 无利息");
        assertEquals(0, t0.payment().compareTo(BigDecimal.ZERO), "T0 无付款");
    }

    @Test
    void generateInterestAccrualAndQuarterlyPayment() {
        // 零利率季付：期初 100000，季末付款 10000，逐月递减
        List<ScheduleRow> rows = LeaseScheduleEngine.generate(
                new BigDecimal("100000"), new BigDecimal("10000"),
                3, 12, BigDecimal.ZERO, "202601");
        // 第 1 月：无付款，期末 100000
        assertEquals(0, rows.get(1).payment().compareTo(BigDecimal.ZERO));
        assertEquals(0, new BigDecimal("100000.00").compareTo(rows.get(1).endBal()));
        // 第 3 月：付款 10000，期末 90000
        assertEquals(0, new BigDecimal("10000.00").compareTo(rows.get(3).payment()));
        assertEquals(0, new BigDecimal("90000.00").compareTo(rows.get(3).endBal()));
        // 末月（12）轧差清零
        assertEquals(0, rows.get(12).endBal().compareTo(BigDecimal.ZERO), "末月期末清零");
    }

    @Test
    void generateInterestAccruesAtMonthlyRate() {
        // 计息合同：期初 100000，月利率 1%（年 12%），第 1 月利息 = 1000
        List<ScheduleRow> rows = LeaseScheduleEngine.generate(
                new BigDecimal("100000"), new BigDecimal("10000"),
                3, 12, new BigDecimal("0.12"), "202601");
        assertEquals(0, new BigDecimal("1000.00").compareTo(rows.get(1).interest()),
                "首月利息 = 100000 × 1%");
        // 第 2 月期初 = 第 1 月期末 = 101000（无付款，利息滚存），利息 = 1010
        assertEquals(0, rows.get(2).beginBal().compareTo(rows.get(1).endBal()), "期初接上期期末");
        assertEquals(0, new BigDecimal("1010.00").compareTo(rows.get(2).interest()),
                "复利滚存：利息随期初递增");
        // 第 3 月付款 10000：实际利率法复利滚存
        // 期末1 = 101000；期末2 = 102010；第 3 月 = 102010 + 1020.10 − 10000 = 93030.10
        assertEquals(0, new BigDecimal("93030.10").compareTo(rows.get(3).endBal()),
                "实际利率法逐月滚存");
        // 递推闭合：每行 期末 = 期初 + 利息 − 付款
        for (int i = 1; i < rows.size(); i++) {
            ScheduleRow r = rows.get(i);
            BigDecimal calc = r.beginBal().add(r.interest()).subtract(r.payment());
            assertEquals(0, calc.compareTo(r.endBal()), "行 " + r.seq() + " 递推闭合");
        }
    }

    @Test
    void remeasureRecomputesRemainingRows() {
        // 已处理 6 月后延长 6 月（12→18）：自第 7 月起重算 12 行（6 剩余 + 6 新增）
        List<ScheduleRow> rows = LeaseScheduleEngine.remeasure(
                new BigDecimal("50000"), 7, "202607",
                new BigDecimal("10000"), 3, 12, BigDecimal.ZERO);
        assertEquals(12, rows.size());
        assertEquals(7, rows.get(0).seq(), "起算月序连续");
        assertEquals("202607", rows.get(0).periodTag());
        assertEquals(0, new BigDecimal("50000.00").compareTo(rows.get(0).beginBal()));
        // 末月清零
        assertEquals(0, rows.get(11).endBal().compareTo(BigDecimal.ZERO), "重计量末月清零");
        // 递推闭合
        for (int i = 1; i < rows.size(); i++) {
            ScheduleRow r = rows.get(i);
            assertEquals(0, r.beginBal().add(r.interest()).subtract(r.payment()).compareTo(r.endBal()));
        }
    }

    @Test
    void invalidArgsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LeaseScheduleEngine.presentValue(BigDecimal.ZERO, 3, 12, RATE_45));
        assertThrows(IllegalArgumentException.class,
                () -> LeaseScheduleEngine.presentValue(new BigDecimal("100"), 0, 12, RATE_45));
        assertThrows(IllegalArgumentException.class,
                () -> LeaseScheduleEngine.generate(BigDecimal.ZERO, new BigDecimal("100"),
                        3, 12, RATE_45, "202601"));
    }
}
