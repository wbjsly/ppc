package com.erp.eam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 租赁摊余成本计划引擎（add-asset-transfer-lease，spec「摊余成本计划与月度租赁处理」/design D5）。
 *
 * <p>纯静态、无状态、无 Spring 依赖，与 {@link DepreciationEngine} 同款风格，可单测精确值。
 * 口径（design D5 默认，记偏差）：付款按月折现、落在各付款期末；月利率 = 年折现率 / 12。
 * <ul>
 *   <li>PV = Σ 付款 / (1 + 月利率)^t（t = 付款所在月序）</li>
 *   <li>计划行（实际利率法）：利息 = 期初负债 × 月利率；期末 = 期初 + 利息 − 付款</li>
 *   <li>T0 行（确认月）：期初 = 期末 = PV，无利息无付款</li>
 *   <li>末月付款轧差：付款 = 期初 + 利息（清零负债，吸收舍入漂移）</li>
 *   <li>金额一律 HALF_UP 保留 2 位</li>
 * </ul>
 */
public final class LeaseScheduleEngine {

    private LeaseScheduleEngine() {
    }

    /** 计划行（seq=0 为 T0 确认月行） */
    public record ScheduleRow(
            int seq,
            String periodTag,
            BigDecimal beginBal,
            BigDecimal interest,
            BigDecimal payment,
            BigDecimal endBal) {
    }

    /** 月利率（年折现率 / 12；空/0 → 0） */
    public static BigDecimal monthlyRate(BigDecimal annualRate) {
        if (annualRate == null || annualRate.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP);
        }
        return annualRate.divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);
    }

    /**
     * 租赁负债现值 PV = Σ 付款 / (1 + 月利率)^t，付款落在各付款期末。
     *
     * @param payment         每期付款额（> 0）
     * @param payCycleMonths  付款周期月数（1 月付 / 3 季付 / 6 半年付 / 12 年付）
     * @param termsMonths     租期月数（> 0）
     * @param annualRate      年折现率（小数 0.045；空按 0）
     * @return PV（2 位小数）
     */
    public static BigDecimal presentValue(BigDecimal payment, int payCycleMonths,
                                          int termsMonths, BigDecimal annualRate) {
        if (payment == null || payment.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("每期付款额必须大于 0");
        }
        if (payCycleMonths <= 0 || termsMonths <= 0) {
            throw new IllegalArgumentException("付款周期与租期月数必须大于 0");
        }
        BigDecimal r = monthlyRate(annualRate);
        BigDecimal one = BigDecimal.ONE;
        BigDecimal pv = BigDecimal.ZERO;
        for (int m = payCycleMonths; m <= termsMonths; m += payCycleMonths) {
            BigDecimal factor = one.add(r).pow(m);           // (1+r)^t
            pv = pv.add(payment.divide(factor, 10, RoundingMode.HALF_UP));
        }
        return pv.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 全周期摊余计划生成（确认时预生成）。
     *
     * @param pv              租赁负债现值（T0 期末负债）
     * @param payment         每期付款额
     * @param payCycleMonths  付款周期月数
     * @param termsMonths     租期月数
     * @param annualRate      年折现率
     * @param startPeriodTag  确认月 yyyyMM（T0 行所属月；次月起逐月计息）
     * @return 行数 = termsMonths + 1（含 T0），按 seq 升序
     */
    public static List<ScheduleRow> generate(BigDecimal pv, BigDecimal payment,
                                             int payCycleMonths, int termsMonths,
                                             BigDecimal annualRate, String startPeriodTag) {
        if (pv == null || pv.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("现值必须大于 0");
        }
        if (termsMonths <= 0) {
            throw new IllegalArgumentException("租期月数必须大于 0");
        }
        BigDecimal r = monthlyRate(annualRate);
        YearMonth start = parse(startPeriodTag);
        List<ScheduleRow> rows = new ArrayList<>(termsMonths + 1);
        // T0 确认月行：期初=期末=PV，无利息无付款
        rows.add(new ScheduleRow(0, startPeriodTag,
                pv.setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2), pv.setScale(2, RoundingMode.HALF_UP)));
        BigDecimal begin = pv.setScale(2, RoundingMode.HALF_UP);
        for (int m = 1; m <= termsMonths; m++) {
            BigDecimal interest = begin.multiply(r).setScale(2, RoundingMode.HALF_UP);
            BigDecimal pay;
            if (m == termsMonths) {
                // 末月轧差清零（吸收逐月舍入漂移；与合同付款的差额为分位级）
                pay = begin.add(interest);
            } else {
                pay = (m % payCycleMonths == 0) ? payment.setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2);
            }
            BigDecimal end = begin.add(interest).subtract(pay).setScale(2, RoundingMode.HALF_UP);
            String tag = start.plusMonths(m).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            rows.add(new ScheduleRow(m, tag, begin, interest, pay, end));
            begin = end;
        }
        return rows;
    }

    /**
     * 重新计量：自指定月序起、按修订后条款重算剩余行（BR-4.13-04；快照归档由调用方落库）。
     *
     * @param beginBal        重算起点期初负债（= 上一已处理行期末）
     * @param fromSeq         起算月序（= 上一已处理行 seq + 1）
     * @param fromPeriodTag   起算月 yyyyMM
     * @param payment         修订后每期付款额
     * @param payCycleMonths  付款周期月数
     * @param remainingMonths 剩余月数（含 fromSeq 当月）
     * @param annualRate      修订后年折现率
     * @return 重算行（不含 T0），seq 连续
     */
    public static List<ScheduleRow> remeasure(BigDecimal beginBal, int fromSeq,
                                              String fromPeriodTag, BigDecimal payment,
                                              int payCycleMonths, int remainingMonths,
                                              BigDecimal annualRate) {
        if (remainingMonths <= 0) {
            throw new IllegalArgumentException("剩余月数必须大于 0");
        }
        BigDecimal r = monthlyRate(annualRate);
        YearMonth start = parse(fromPeriodTag);
        List<ScheduleRow> rows = new ArrayList<>(remainingMonths);
        BigDecimal begin = beginBal.setScale(2, RoundingMode.HALF_UP);
        for (int i = 0; i < remainingMonths; i++) {
            int seq = fromSeq + i;
            BigDecimal interest = begin.multiply(r).setScale(2, RoundingMode.HALF_UP);
            boolean last = i == remainingMonths - 1;
            boolean payMonth = (seq % payCycleMonths == 0);
            BigDecimal pay;
            if (last) {
                pay = begin.add(interest);      // 末月清零
            } else {
                pay = payMonth ? payment.setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO.setScale(2);
            }
            BigDecimal end = begin.add(interest).subtract(pay).setScale(2, RoundingMode.HALF_UP);
            String tag = start.plusMonths(i).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            rows.add(new ScheduleRow(seq, tag, begin, interest, pay, end));
            begin = end;
        }
        return rows;
    }

    private static YearMonth parse(String periodTag) {
        if (periodTag == null || periodTag.length() != 6) {
            throw new IllegalArgumentException("会计月格式须为 yyyyMM：" + periodTag);
        }
        return YearMonth.parse(periodTag.substring(0, 4) + "-" + periodTag.substring(4));
    }
}
