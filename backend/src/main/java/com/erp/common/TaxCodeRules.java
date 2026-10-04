package com.erp.common;

import com.erp.entity.mdm.MdmTaxCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 税码校验纯函数核心（FR-4.1-3-2 + BR-4.1-16 + C-4.1-04，与 ExchangeRateRules 同构）。
 * 单条路径：返回非 null 原因 → 调用方抛 422；
 * 批量路径：同一原因作为行级失败 reason 收集 —— 区间判定委托 IntervalRules 单点。
 */
public final class TaxCodeRules {

    /** 税率值精度（百分数 4 位小数） */
    public static final int RATE_SCALE = 4;

    public static final List<String> SCOPES = List.of("DOMESTIC", "EXPORT", "EXEMPT");
    public static final List<String> CALC_TYPES = List.of("GENERAL", "SIMPLIFIED", "DIFFERENTIAL");
    public static final List<String> RATE_KINDS = List.of("STANDARD", "LOW", "ZERO", "EXEMPT");

    private TaxCodeRules() {
    }

    /** 字段校验（编号格式/枚举/税率精度与矛盾/日期关系/政策文号），返回 null=通过 */
    public static String validateFields(MdmTaxCode t) {
        if (t.getTaxCode() == null || !t.getTaxCode().trim().toUpperCase()
                .matches("[A-Z0-9][A-Z0-9-]{1,31}")) {
            return "税码编号须为 2~32 位大写字母、数字与短横线（如 VAT-13）";
        }
        if (t.getScope() == null || !SCOPES.contains(t.getScope())) {
            return "适用范围须为 国内(DOMESTIC)/出口(EXPORT)/免税(EXEMPT) 之一";
        }
        if (t.getCalcType() == null || !CALC_TYPES.contains(t.getCalcType())) {
            return "计税方式须为 一般(GENERAL)/简易(SIMPLIFIED)/差额(DIFFERENTIAL) 之一";
        }
        if (t.getRateKind() == null || !RATE_KINDS.contains(t.getRateKind())) {
            return "税率类型须为 标准(STANDARD)/低税率(LOW)/零税率(ZERO)/免税(EXEMPT) 之一";
        }
        if (t.getTaxRate() == null) {
            return "税率值必填（BR-4.1-16）";
        }
        if (t.getTaxRate().signum() < 0 || t.getTaxRate().compareTo(new BigDecimal("100")) > 0) {
            return "税率值须在 0 ~ 100 之间";
        }
        boolean zeroKind = "ZERO".equals(t.getRateKind()) || "EXEMPT".equals(t.getRateKind());
        if (t.getTaxRate().signum() == 0 && !zeroKind) {
            return "税率为 0 时税率类型须为 零税率(ZERO) 或 免税(EXEMPT)（与税率类型矛盾）";
        }
        if (t.getTaxRate().signum() > 0 && zeroKind) {
            return "税率类型为零税率/免税时税率值须为 0";
        }
        if (t.getEffectiveDate() == null) {
            return "生效日期必填（BR-4.1-16 / C-4.1-04）";
        }
        if (t.getExpireDate() == null) {
            return "失效日期必填（区间闭合语义，BR-4.1-16）";
        }
        if (t.getExpireDate().isBefore(t.getEffectiveDate())) {
            return "失效日期不得早于生效日期";
        }
        if (!isNotBlank(t.getPolicyNo())) {
            return "政策文号必填（C-4.1-04，如 税总公告2026年第15号）";
        }
        return null;
    }

    /** 按精度归一税率（四舍五入） */
    public static BigDecimal normalizeRate(BigDecimal rate) {
        return rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }

    public static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
