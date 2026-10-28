package com.erp.service.scm;

import java.util.Map;

/** CPFR 时限判定（spec cpfr-collaboration R3，design D2 惰性幂等）。 */
public interface CpfrDeadlineService {

    /**
     * 对某协议某周期实时判定催办/升级/默认采纳（幂等：重复调用不重复生成）。
     * 超 48h 未反馈 → 已催办；超 72h → 已升级（ROLE_PMGR）+ 按上轮量默认采纳。
     *
     * @return 推进摘要 {reminded, escalated, defaulted}
     */
    Map<String, Object> evaluate(String agreementId, String periodTag);

    /** 扫描所有已启用协议的全部待反馈周期（管理端列表/门户进入时调用） */
    Map<String, Object> evaluateAll();
}
