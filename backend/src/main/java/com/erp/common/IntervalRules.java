package com.erp.common;

import java.time.LocalDate;
import java.util.List;

/**
 * 区间衔接判定共享核心（BR-4.1-17，从 ExchangeRateRules 原样迁移，design add-tax-code D2）。
 * 算法单点：汇率（ExchangeRateRules.checkInterval）与税码（TaxCodeRules）均委托此处，防两域漂移。
 */
public final class IntervalRules {

    /** 区间段最小语义（生效日 + 失效日），与具体实体解耦 */
    public record IntervalSeg(LocalDate effective, LocalDate expire) {
    }

    private IntervalRules() {
    }

    /**
     * seq = 既有序列 ∪ 批内前序已接受段（调用方合并），返回 null=通过；
     * 否则含可操作口径的失败原因。
     * 判定顺序：① 相交（闭区间共享端点不算）→ ② 空序列放行 → ③ 贴合链首/链尾放行
     * → ④ 右断档 / 左缺口 → ⑤ 兜底。
     */
    public static String check(List<IntervalSeg> seq, LocalDate from, LocalDate to) {
        if (seq == null || seq.isEmpty()) {
            return null; // 首条放行
        }
        for (IntervalSeg e : seq) {
            if (!from.isAfter(e.expire()) && !e.effective().isAfter(to)) {
                return "生效区间与既有区间相交（BR-4.1-17），冲突区间：["
                        + e.effective() + ", " + e.expire() + "]，请修正后重新提交";
            }
        }
        LocalDate maxExpire = seq.stream().map(IntervalSeg::expire)
                .max(LocalDate::compareTo).orElseThrow();
        LocalDate minEffective = seq.stream().map(IntervalSeg::effective)
                .min(LocalDate::compareTo).orElseThrow();
        boolean touchesTail = from.equals(maxExpire.plusDays(1));
        boolean touchesHead = to.equals(minEffective.minusDays(1));
        if (touchesTail || touchesHead) {
            return null;
        }
        if (from.isAfter(maxExpire)) {
            return "区间断档（BR-4.1-17），新记录生效日须 ≤ "
                    + maxExpire.plusDays(1) + "（衔接上一条失效日），当前生效日 " + from;
        }
        if (to.isBefore(minEffective)) {
            return "区间左侧缺口（BR-4.1-17 对称），新记录失效日须 ≥ "
                    + minEffective.minusDays(1) + "（衔接最早一条生效日），当前失效日 " + to;
        }
        return "区间与既有区间链关系非法（重叠或断档），请对照同序列区间 ["
                + minEffective + ", " + maxExpire + "] 修正";
    }

    /**
     * 邻接式判定（add-tax-policy-workbench D3 修订）：不要求贴全局链首/链尾，
     * 只要求 ① 不相交 ② 左侧若有段则紧贴其尾 ③ 右侧若有段则紧贴其头 —— 支持中间段插入。
     * 与 check 的差异：check 面向「链端追加」，本方法面向「链内任意位置插入」。
     * 返回 null=通过；否则含可操作口径的失败原因。
     */
    public static String checkAdjacent(List<IntervalSeg> seq, LocalDate from, LocalDate to) {
        if (seq == null || seq.isEmpty()) {
            return null; // 首条放行
        }
        LocalDate leftMax = null;   // 严格在 from 左侧的段中最晚失效日
        LocalDate rightMin = null;  // 严格在 to 右侧的段中最早生效日
        for (IntervalSeg e : seq) {
            if (!from.isAfter(e.expire()) && !e.effective().isAfter(to)) {
                return "生效区间与既有区间相交（BR-4.1-17），冲突区间：["
                        + e.effective() + ", " + e.expire() + "]，请修正后重新提交";
            }
            if (e.expire().isBefore(from)) {
                if (leftMax == null || e.expire().isAfter(leftMax)) {
                    leftMax = e.expire();
                }
            }
            if (!e.effective().isBefore(to.plusDays(1))) {
                if (rightMin == null || e.effective().isBefore(rightMin)) {
                    rightMin = e.effective();
                }
            }
        }
        if (leftMax != null && !from.equals(leftMax.plusDays(1))) {
            return "区间断档（BR-4.1-17），新记录生效日须为左侧区间之后的衔接日（"
                    + leftMax.plusDays(1) + "），当前生效日 " + from;
        }
        if (rightMin != null && !to.equals(rightMin.minusDays(1))) {
            return "区间断档（BR-4.1-17），新记录失效日须衔接右侧区间（"
                    + rightMin.minusDays(1) + "），当前失效日 " + to;
        }
        return null;
    }
}
