package com.erp.service.proc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 年度采购预算能力契约（change add-framework-agreement-order，spec purchase-budget）。
 * 轻量预算表：科目 × 年度唯一、无审批立即生效、修改留痕；
 * 消费方 = 价控第 3 级（BR-4.2-17 / C-4.2-03）与 PO 审批预算明细。
 */
public interface ProcBudgetService {

    /** 按年度/关键字查询（含本年累计与阈值换算的使用视图） */
    List<Map<String, Object>> list(Integer year, String keyword);

    /** 使用视图：预算行 ∪ 有累计无预算行（NO_BUDGET 标注），含 triggerAmt = 预算 × 110% */
    List<Map<String, Object>> usage(Integer year);

    /** 录入/修改（同年度同科目唯一，修改须填变更原因，留痕） */
    Map<String, Object> save(Map<String, Object> payload);

    /** 单品类本年累计（价控第 3 级消费；仅 APPROVED 口径） */
    BigDecimal usedFor(String categoryCode, int year);

    /** 单品类预算（无记录返回 null → 调用方按 NO_BUDGET 放行） */
    Map<String, Object> budgetOf(String categoryCode, int year);
}
