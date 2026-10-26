package com.erp.service.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.TaxPreferenceDao;
import com.erp.entity.fin.TaxPreference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠适用申报校验器（BR-4.14-05 / S-4.14-11）。
 * 由 tax-filing 生成侧（generate/generateCit）注入调用——越界 422 阻断并输出越界明细；
 * 无相关有效备案时含优惠数据同样阻断。validator 无状态，不依赖 TaxFilingService（防循环）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxPreferenceValidator {

    private final TaxPreferenceDao prefDao;

    /**
     * 申报生成前校验（design D2）。
     *
     * @param period      申报所属期 yyyyMM
     * @param taxType     VAT / CIT
     * @param vatRefundAmount   本期拟享受即征即退税额（VAT 传入，null/0 视为不享受）
     * @param vatScopeIncome    本期备案范围口径收入（软件产品收入等，VAT 传入）
     * @param rndSuperDeduction 本期加计扣除归集研发费用金额（CIT 传入）
     * @throws ServiceException 422 越界/无备案（文案含「BR-4.14-05」与越界明细）
     */
    public void validateForFiling(String period, String taxType,
                                  BigDecimal vatRefundAmount, BigDecimal vatScopeIncome,
                                  BigDecimal rndSuperDeduction) {
        LocalDate periodEnd = endOfPeriod(period);
        List<TaxPreference> actives = prefDao.selectList(new LambdaQueryWrapper<TaxPreference>()
                .eq(TaxPreference::getDelFlag, "0")
                .eq(TaxPreference::getStatus, TaxPreference.ST_ACTIVE)
                .le(TaxPreference::getPrefStart, periodEnd)
                .ge(TaxPreference::getPrefEnd, periodEnd));
        List<Map<String, Object>> violations = new ArrayList<>();

        boolean wantsVatRefund = vatRefundAmount != null && vatRefundAmount.signum() > 0;
        boolean wantsRndSuper = rndSuperDeduction != null && rndSuperDeduction.signum() > 0;

        if (wantsVatRefund) {
            TaxPreference match = actives.stream()
                    .filter(p -> TaxPreference.TYPE_VAT_REFUND.equals(p.getPrefType()))
                    .findFirst().orElse(null);
            if (match == null) {
                violations.add(violation(null, "即征即退适用",
                        "无当期有效备案（申报数据须落在备案范围内）", vatRefundAmount));
            } else {
                BigDecimal cap = scopeIncomeCap(match);
                if (cap != null && vatScopeIncome != null && vatScopeIncome.compareTo(cap) > 0) {
                    violations.add(violation(match.getPrefNo(), "备案范围收入",
                            "本期收入超出备案范围上限", vatScopeIncome.subtract(cap)));
                }
            }
        }
        if (wantsRndSuper) {
            TaxPreference match = actives.stream()
                    .filter(p -> TaxPreference.TYPE_RND_SUPER.equals(p.getPrefType()))
                    .findFirst().orElse(null);
            if (match == null) {
                violations.add(violation(null, "加计扣除适用",
                        "无当期有效备案（研发费用须在备案口径内归集）", rndSuperDeduction));
            } else if (!hasRndScope(match)) {
                violations.add(violation(match.getPrefNo(), "备案口径科目",
                        "备案未登记研发费用科目口径，无法核验加计扣除归集", rndSuperDeduction));
            }
        }
        // 冻结/过期拦截：期内任一相关备案处于 FROZEN/EXPIRED 且拟享受对应优惠
        if (wantsVatRefund || wantsRndSuper) {
            List<TaxPreference> blocked = prefDao.selectList(new LambdaQueryWrapper<TaxPreference>()
                    .eq(TaxPreference::getDelFlag, "0")
                    .in(TaxPreference::getStatus, TaxPreference.ST_FROZEN, TaxPreference.ST_EXPIRED)
                    .le(TaxPreference::getPrefStart, periodEnd)
                    .ge(TaxPreference::getPrefEnd, periodEnd));
            for (TaxPreference b : blocked) {
                boolean relevant = (wantsVatRefund && TaxPreference.TYPE_VAT_REFUND.equals(b.getPrefType()))
                        || (wantsRndSuper && TaxPreference.TYPE_RND_SUPER.equals(b.getPrefType()));
                if (relevant) {
                    violations.add(violation(b.getPrefNo(), "备案状态",
                            "备案已过期/冻结（" + (b.getFrozenReason() == null ? "条件不满足" : b.getFrozenReason()) + "）",
                            null));
                }
            }
        }
        if (!violations.isEmpty()) {
            String detail;
            try {
                detail = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(violations);
            } catch (Exception e) {
                detail = violations.toString();
            }
            throw new ServiceException(422,
                    "BR-4.14-05 优惠适用校验未通过：申报数据须落在备案范围内，越界明细：" + detail);
        }
    }

    /** 备案范围收入上限（PREF_SCOPE_JSON.capIncome，未配置返回 null=不限额） */
    private BigDecimal scopeIncomeCap(TaxPreference p) {
        String json = p.getPrefScopeJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            var cap = node.get("capIncome");
            return cap == null || cap.isNull() ? null : new BigDecimal(cap.asText());
        } catch (Exception e) {
            log.warn("pref scope json parse failed: {}", p.getPrefNo());
            return null;
        }
    }

    /** 备案是否登记研发费用科目口径（rndAccounts 非空） */
    private boolean hasRndScope(TaxPreference p) {
        String json = p.getPrefScopeJson();
        if (json == null || json.isBlank()) {
            return true; // 未配置口径视为不限（政策口径由人工备案材料保证）
        }
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            var accounts = node.get("rndAccounts");
            return accounts != null && accounts.isArray() && accounts.size() > 0;
        } catch (Exception e) {
            return true;
        }
    }

    private static Map<String, Object> violation(String prefNo, String check, String reason, BigDecimal amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefNo", prefNo);
        m.put("check", check);
        m.put("reason", reason);
        m.put("amount", amount == null ? null : amount.stripTrailingZeros().toPlainString());
        return m;
    }

    /** yyyyMM 所属期末日 */
    private static LocalDate endOfPeriod(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        return LocalDate.of(y, m, 1).withDayOfMonth(LocalDate.of(y, m, 1).lengthOfMonth());
    }
}
