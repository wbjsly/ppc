package com.erp.eam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 共享折旧计算引擎（add-asset-depreciation，spec「三方法折旧计算引擎」/design D-1）。
 *
 * <p>纯静态工具、无状态、无 Spring 依赖，供 7.2 月度计提与 6.7 转资折旧预览复用，
 * 保证两处口径一致。支持三种折旧方法（BR-4.6-27）：
 * <ul>
 *   <li>{@link #METHOD_SL}：直线法，月折旧 = (原值 − 残值) ÷ 年限 ÷ 12，残值 = 原值 × 残值率</li>
 *   <li>{@link #METHOD_DDB}：双倍余额递减法，月折旧 = 期初 NBV × 2 ÷ 年限 ÷ 12，末年切直线补差提足</li>
 *   <li>{@link #METHOD_SYD}：年数总和法，月折旧 = (原值 − 残值) × 剩余年数 ÷ 年数总和 ÷ 12</li>
 * </ul>
 *
 * <p>统一语义（所有方法共用）：
 * <ul>
 *   <li>提足截减（BR-4.13-12）：本期计提后累计折旧将超过 原值 − 残值 时，截减至上限并置「已提足」</li>
 *   <li>残值预警（C-4.6-04）：残值 > NBV × DEPRECIATION_RESIDUAL_ALERT（默认 10%）时标记，不阻断</li>
 *   <li>金额一律 HALF_UP 保留 2 位</li>
 * </ul>
 */
public final class DepreciationEngine {

    /** 直线法 */
    public static final String METHOD_SL = "STRAIGHT_LINE";
    /** 双倍余额递减法 */
    public static final String METHOD_DDB = "DOUBLE_DECLINING";
    /** 年数总和法 */
    public static final String METHOD_SYD = "SYD";

    /** 残值占 NBV 预警默认阈值（C-4.6-04） */
    public static final BigDecimal DEFAULT_RESIDUAL_ALERT_THRESHOLD = new BigDecimal("0.10");

    private DepreciationEngine() {
    }

    /** 单期折旧计算结果 */
    public record DepreciationResult(
            BigDecimal beginNbv,
            BigDecimal periodDepreciation,
            BigDecimal endAccumDepreciation,
            BigDecimal endNbv,
            boolean capped,
            boolean fullyDepreciated,
            boolean residualAlert) {
    }

    /**
     * 起提月（BR-4.13-11：启用当月不计提，次月起计提）。
     *
     * @param effectiveDate 启用日期
     * @return 起提月 yyyyMM
     */
    public static String startMonth(LocalDate effectiveDate) {
        if (effectiveDate == null) {
            return null;
        }
        return YearMonth.from(effectiveDate).plusMonths(1).toString().replace("-", "");
    }

    /**
     * 某会计月是否已到起提月（含）。
     *
     * @param periodTag  会计月 yyyyMM
     * @param startMonth 起提月 yyyyMM（可空，空视为未到）
     */
    public static boolean isDepreciable(String periodTag, String startMonth) {
        if (periodTag == null || startMonth == null || startMonth.length() != 6) {
            return false;
        }
        return periodTag.compareTo(startMonth) >= 0;
    }

    /**
     * 已计提月数（自起提月含至会计月含）。
     */
    public static int monthsDepreciated(String periodTag, String startMonth) {
        if (!isDepreciable(periodTag, startMonth)) {
            return 0;
        }
        YearMonth s = YearMonth.parse(startMonth.substring(0, 4) + "-" + startMonth.substring(4));
        YearMonth p = YearMonth.parse(periodTag.substring(0, 4) + "-" + periodTag.substring(4));
        return (int) (p.getYear() - s.getYear()) * 12 + (p.getMonthValue() - s.getMonthValue()) + 1;
    }

    /**
     * 计算某卡在指定会计月的本期折旧。
     *
     * @param assetCost        原值（> 0）
     * @param residualValue    残值（≥ 0；由原值 × 残值率得来，调用方算好传入）
     * @param lifeYears        使用年限（> 0）
     * @param method           折旧方法（METHOD_SL / METHOD_DDB / METHOD_SYD）
     * @param alreadyMonths    本期之前的已计提月数（不含本期）
     * @param beginAccum       上期累计折旧（本期计提前）
     * @param impairment       减值准备（≥ 0；BR-4.13-15 减值后以减值后 NBV 为折旧基数）
     * @param residualAlertThreshold 残值预警阈值（传 null 用默认 10%）
     * @return 本期折旧结果（含提足截减与残值预警标记）
     */
    public static DepreciationResult compute(BigDecimal assetCost,
                                             BigDecimal residualValue,
                                             int lifeYears,
                                             String method,
                                             int alreadyMonths,
                                             BigDecimal beginAccum,
                                             BigDecimal impairment,
                                             BigDecimal residualAlertThreshold) {
        requirePositive(assetCost, "原值");
        if (lifeYears <= 0) {
            throw new IllegalArgumentException("使用年限必须大于 0");
        }
        BigDecimal residual = nz(residualValue);
        BigDecimal accum = nz(beginAccum);
        BigDecimal impair = nz(impairment);
        String m = method == null || method.isBlank() ? METHOD_SL : method;

        // 可折旧总额上限 = 原值 − 残值；NBV = 原值 − 累计折旧 − 减值准备
        BigDecimal maxDepreciable = assetCost.subtract(residual);
        BigDecimal beginNbv = assetCost.subtract(accum).subtract(impair);

        // 残值预警：残值 > NBV × 阈值（仅在 NBV > 0 时有意义）
        BigDecimal threshold = nz(residualAlertThreshold);
        if (threshold.compareTo(BigDecimal.ZERO) <= 0) {
            threshold = DEFAULT_RESIDUAL_ALERT_THRESHOLD;
        }
        boolean residualAlert = beginNbv.compareTo(BigDecimal.ZERO) > 0
                && residual.compareTo(beginNbv.multiply(threshold)) > 0;

        // 剩余可提额（不能把累计折旧提到超过上限）
        BigDecimal remainingCap = maxDepreciable.subtract(accum);
        if (remainingCap.compareTo(BigDecimal.ZERO) <= 0) {
            // 已提足，本期不再计提
            return new DepreciationResult(beginNbv, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                    accum.setScale(2, RoundingMode.HALF_UP),
                    beginNbv.setScale(2, RoundingMode.HALF_UP), false, true, residualAlert);
        }

        BigDecimal raw = rawMonthly(assetCost, residual, lifeYears, m, alreadyMonths, beginNbv, impair);
        BigDecimal period = raw.min(remainingCap);
        period = period.setScale(2, RoundingMode.HALF_UP);
        // 截减后再夹一次，防止 scale 进位越过上限
        if (period.compareTo(remainingCap) > 0) {
            period = remainingCap.setScale(2, RoundingMode.HALF_UP);
        }
        boolean capped = raw.setScale(2, RoundingMode.HALF_UP).compareTo(period) > 0;

        BigDecimal endAccum = accum.add(period);
        BigDecimal endNbv = assetCost.subtract(endAccum).subtract(impair);
        boolean fully = endAccum.compareTo(maxDepreciable) >= 0
                || (m.equals(METHOD_DDB) && alreadyMonths + 1 >= lifeYears * 12)
                || (m.equals(METHOD_SYD) && alreadyMonths + 1 >= lifeYears * 12);

        return new DepreciationResult(beginNbv.setScale(2, RoundingMode.HALF_UP),
                period, endAccum.setScale(2, RoundingMode.HALF_UP),
                endNbv.setScale(2, RoundingMode.HALF_UP), capped, fully, residualAlert);
    }

    /**
     * 本期「毛」折旧（未做提足截减）。
     *
     * @param beginNbv 期初账面净值（已扣减值）
     * @param impair   减值准备（DDB/SYD 基数用）
     */
    private static BigDecimal rawMonthly(BigDecimal assetCost,
                                         BigDecimal residual,
                                         int lifeYears,
                                         String method,
                                         int alreadyMonths,
                                         BigDecimal beginNbv,
                                         BigDecimal impair) {
        int totalMonths = lifeYears * 12;
        switch (method) {
            case METHOD_DDB: {
                // 月折旧 = 期初 NBV × 2 ÷ 年限 ÷ 12；末年（最后 12 个月）切直线补差提足
                if (alreadyMonths >= totalMonths - 12) {
                    // 末年：直线补差，保证提足时 NBV = 残值
                    int remainMonths = totalMonths - alreadyMonths;
                    if (remainMonths <= 0) {
                        return BigDecimal.ZERO;
                    }
                    return beginNbv.subtract(residual).divide(
                            BigDecimal.valueOf(remainMonths), 2, RoundingMode.HALF_UP);
                }
                BigDecimal rate = BigDecimal.valueOf(2)
                        .divide(BigDecimal.valueOf(lifeYears), 10, RoundingMode.HALF_UP);
                return beginNbv.multiply(rate)
                        .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            }
            case METHOD_SYD: {
                // 月折旧 = (原值 − 残值) × 剩余年数 ÷ 年数总和 ÷ 12
                BigDecimal depreciable = assetCost.subtract(residual);
                BigDecimal yearSum = BigDecimal.valueOf((long) lifeYears * (lifeYears + 1) / 2);
                int currentYear = alreadyMonths / 12 + 1;           // 第几年
                int remainYears = lifeYears - currentYear + 1;      // 剩余年数（含当年）
                if (remainYears <= 0) {
                    return BigDecimal.ZERO;
                }
                return depreciable.multiply(BigDecimal.valueOf(remainYears))
                        .divide(yearSum, 2, RoundingMode.HALF_UP)
                        .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            }
            case METHOD_SL:
            default: {
                // 月折旧 = (原值 − 残值) ÷ 年限 ÷ 12
                BigDecimal depreciable = assetCost.subtract(residual);
                return depreciable.divide(BigDecimal.valueOf(totalMonths), 2, RoundingMode.HALF_UP);
            }
        }
    }

    private static void requirePositive(BigDecimal v, String name) {
        if (v == null || v.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(name + "必须大于 0");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
