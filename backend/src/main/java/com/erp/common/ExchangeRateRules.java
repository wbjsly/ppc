package com.erp.common;

import com.erp.entity.mdm.MdmExchangeRate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 汇率校验纯函数核心（BR-4.1-16/17 + C-4.1-04，design add-exchange-rate-batch D2）。
 * 单条路径：返回非 null 原因 → 调用方抛 422；
 * 批量路径：同一原因作为行级失败 reason 收集 —— 算法单点，防批量复制漂移。
 */
public final class ExchangeRateRules {

    private ExchangeRateRules() {
    }

    /** 字段校验（必填四件/ISO/精度前值检查/日期关系），返回 null=通过 */
    public static String validateFields(MdmExchangeRate r) {
        if (!isIsoCcy(r.getBaseCcy())) {
            return "基础币种须为 3 位大写 ISO 4217 代码（如 CNY）";
        }
        if (!isIsoCcy(r.getQuoteCcy())) {
            return "报价币种须为 3 位大写 ISO 4217 代码（如 USD）";
        }
        if (r.getBaseCcy().equals(r.getQuoteCcy())) {
            return "基础币种与报价币种不得相同";
        }
        if (r.getRateType() == null || r.getRateType().isBlank()
                || !List.of("MIDDLE", "BUY", "SELL").contains(r.getRateType())) {
            return "汇率类型须为 中间价(MIDDLE)/买入价(BUY)/卖出价(SELL) 之一";
        }
        if (r.getRate() == null || r.getRate().signum() <= 0) {
            return "汇率值须大于 0（BR-4.1-16）";
        }
        if (r.getEffectiveDate() == null) {
            return "生效日期必填（BR-4.1-16 / C-4.1-04）";
        }
        if (r.getExpireDate() == null) {
            return "失效日期必填（区间闭合语义，BR-4.1-16）";
        }
        if (r.getExpireDate().isBefore(r.getEffectiveDate())) {
            return "失效日期不得早于生效日期";
        }
        if (r.getSourceFileNo() == null || r.getSourceFileNo().isBlank()) {
            return "来源文件编号必填（C-4.1-04，如央行公告编号）";
        }
        return null;
    }

    /**
     * 区间衔接判定（BR-4.1-17）：薄委托至共享核心 IntervalRules（add-tax-code D2 抽取，
     * 行为不变）；seq 合并语义见 IntervalRules.check。
     */
    public static String checkInterval(List<MdmExchangeRate> seq, LocalDate from, LocalDate to) {
        if (seq == null || seq.isEmpty()) {
            return null; // 首条放行
        }
        List<IntervalRules.IntervalSeg> segs = seq.stream()
                .map(r -> new IntervalRules.IntervalSeg(r.getEffectiveDate(), r.getExpireDate()))
                .toList();
        return IntervalRules.check(segs, from, to);
    }

    private static boolean isIsoCcy(String ccy) {
        return ccy != null && ccy.matches("[A-Z]{3}");
    }

    /** 供批量/单条共用的非空判定 */
    public static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 对象相等辅助（批量 diff 用） */
    public static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
