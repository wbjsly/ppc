package com.erp.eam;

import com.erp.eam.DepreciationEngine.DepreciationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * add-asset-depreciation：共享折旧计算引擎（spec「三方法折旧计算引擎」）。
 * 纯函数测试，精确值锚定三方法口径 + 提足截减 + 残值预警 + 起提月派生。
 */
class DepreciationEngineTest {

    private static final BigDecimal COST_120W = new BigDecimal("1200000");
    private static final BigDecimal RESIDUAL_5PCT = new BigDecimal("60000");   // 120万 × 5%
    private static final BigDecimal COST_100W = new BigDecimal("1000000");
    private static final BigDecimal RESIDUAL_100W_5PCT = new BigDecimal("50000");

    // ---------- 起提月（BR-4.13-11）----------

    @Test
    void startMonthIsNextMonthOfEffectiveDate() {
        assertEquals("202611", DepreciationEngine.startMonth(java.time.LocalDate.of(2026, 10, 8)));
        // 12 月启用 → 次年 1 月起提
        assertEquals("202701", DepreciationEngine.startMonth(java.time.LocalDate.of(2026, 12, 31)));
    }

    @Test
    void isDepreciableFromStartMonthOnward() {
        assertTrue(DepreciationEngine.isDepreciable("202611", "202611"));
        assertTrue(DepreciationEngine.isDepreciable("202612", "202611"));
        assertFalse(DepreciationEngine.isDepreciable("202610", "202611"));
    }

    // ---------- 直线法 SL ----------

    @Test
    void slMonthlyIsStraightLine() {
        // 100万，5%，10年 → (100万−5万)/120 = 7916.67
        DepreciationResult r = DepreciationEngine.compute(
                COST_100W, RESIDUAL_100W_5PCT, 10, DepreciationEngine.METHOD_SL,
                0, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertEquals(new BigDecimal("7916.67"), r.periodDepreciation());
        assertFalse(r.capped());
        assertFalse(r.fullyDepreciated());
    }

    @Test
    void slCappedAtResidualOnFinalMonth() {
        // 已提 119 月，累计 = 7916.67×119 = 942083.73；上限 = 100万−5万 = 95万
        // 剩余可提 = 950000 − 942083.73 = 7916.27 < 7916.67 → 截减至 7916.27
        BigDecimal beginAccum = new BigDecimal("7916.67").multiply(new BigDecimal("119"));
        DepreciationResult r = DepreciationEngine.compute(
                COST_100W, RESIDUAL_100W_5PCT, 10, DepreciationEngine.METHOD_SL,
                119, beginAccum, BigDecimal.ZERO, null);
        assertEquals(new BigDecimal("7916.27"), r.periodDepreciation());
        assertTrue(r.capped());
        assertTrue(r.fullyDepreciated());
        // 期末累计 = 上限 95万
        assertEquals(0, new BigDecimal("950000").compareTo(r.endAccumDepreciation()));
    }

    @Test
    void slFullyDepreciatedYieldsZero() {
        BigDecimal accum = new BigDecimal("950000");
        DepreciationResult r = DepreciationEngine.compute(
                COST_100W, RESIDUAL_100W_5PCT, 10, DepreciationEngine.METHOD_SL,
                120, accum, BigDecimal.ZERO, null);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.periodDepreciation()));
        assertTrue(r.fullyDepreciated());
    }

    // ---------- 双倍余额递减法 DDB ----------

    @Test
    void ddbFirstMonthUsesDoubleRate() {
        // 120万，10年，首月 NBV=120万 → 120万×2/10/12 = 2万
        DepreciationResult r = DepreciationEngine.compute(
                COST_120W, RESIDUAL_5PCT, 10, DepreciationEngine.METHOD_DDB,
                0, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertEquals(new BigDecimal("20000.00"), r.periodDepreciation());
    }

    @Test
    void ddbFinalYearSwitchesToStraightLine() {
        // 末年（已提108月）切直线补差：期初NBV − 残值 ÷ 剩余12月
        // NBV = 120万 − 累计；构造累计 = 120万 − 30万 = 90万（NBV=30万）
        BigDecimal beginAccum = new BigDecimal("900000");
        DepreciationResult r = DepreciationEngine.compute(
                COST_120W, RESIDUAL_5PCT, 10, DepreciationEngine.METHOD_DDB,
                108, beginAccum, BigDecimal.ZERO, null);
        // 末年直线 = (30万 − 6万)/12 = 2万
        assertEquals(new BigDecimal("20000.00"), r.periodDepreciation());
    }

    // ---------- 年数总和法 SYD ----------

    @Test
    void sydFirstYearUsesFullRemainingYears() {
        // 120万，5%残值=6万，可折旧=114万；年数总和=10×11/2=55；第1年剩余10年
        // 月 = 114万 × 10 / 55 / 12 = 17272.73
        DepreciationResult r = DepreciationEngine.compute(
                COST_120W, RESIDUAL_5PCT, 10, DepreciationEngine.METHOD_SYD,
                0, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertEquals(new BigDecimal("17272.73"), r.periodDepreciation());
    }

    @Test
    void sydFinalYearUsesOneRemainingYear() {
        // 第10年（已提108月）剩余1年：月 = 114万 × 1 / 55 / 12 = 1727.27
        DepreciationResult r = DepreciationEngine.compute(
                COST_120W, RESIDUAL_5PCT, 10, DepreciationEngine.METHOD_SYD,
                108, BigDecimal.ZERO, BigDecimal.ZERO, null);
        assertEquals(new BigDecimal("1727.27"), r.periodDepreciation());
    }

    // ---------- 残值预警（C-4.6-04）----------

    @Test
    void residualAlertTriggersWhenRatioExceedsThreshold() {
        // 残值6万，NBV 50万 → 6万/50万 = 12% > 10% → 预警
        DepreciationResult r = DepreciationEngine.compute(
                COST_120W, RESIDUAL_5PCT, 10, DepreciationEngine.METHOD_SL,
                0, new BigDecimal("650000"), BigDecimal.ZERO, null);
        // NBV = 120万 − 65万 = 55万；6万/55万 = 10.9% > 10% → 预警
        assertTrue(r.residualAlert());
    }

    @Test
    void residualAlertNotTriggeredWhenRatioBelowThreshold() {
        // 残值6万，NBV 90万 → 6.7% < 10% → 无预警
        DepreciationResult r = DepreciationEngine.compute(
                COST_120W, RESIDUAL_5PCT, 10, DepreciationEngine.METHOD_SL,
                60, new BigDecimal("300000"), BigDecimal.ZERO, null);
        // NBV = 120万 − 30万 = 90万；6万/90万 = 6.7% → 无预警
        assertFalse(r.residualAlert());
    }

    // ---------- 减值后重算（BR-4.13-15）----------

    @Test
    void impairmentReducesDepreciableBase() {
        // 减值20万后 NBV = 120万 − 0 − 20万 = 100万；末年直线补差基数 = NBV − 残值
        DepreciationResult r = DepreciationEngine.compute(
                COST_120W, RESIDUAL_5PCT, 10, DepreciationEngine.METHOD_DDB,
                108, BigDecimal.ZERO, new BigDecimal("200000"), null);
        // 末年直线 = (100万 − 6万)/12 = 78333.33
        assertEquals(new BigDecimal("78333.33"), r.periodDepreciation());
    }

    // ---------- 入参校验 ----------

    @Test
    void invalidArgsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DepreciationEngine.compute(
                BigDecimal.ZERO, RESIDUAL_5PCT, 10, DepreciationEngine.METHOD_SL,
                0, BigDecimal.ZERO, BigDecimal.ZERO, null));
        assertThrows(IllegalArgumentException.class, () -> DepreciationEngine.compute(
                COST_120W, RESIDUAL_5PCT, 0, DepreciationEngine.METHOD_SL,
                0, BigDecimal.ZERO, BigDecimal.ZERO, null));
    }

    @Test
    void monthsDepreciatedCountsInclusive() {
        assertEquals(0, DepreciationEngine.monthsDepreciated("202610", "202611"));
        assertEquals(1, DepreciationEngine.monthsDepreciated("202611", "202611"));
        assertEquals(13, DepreciationEngine.monthsDepreciated("202711", "202611"));
    }
}
