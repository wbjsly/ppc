package com.erp.service.inv;

import java.util.Map;

/**
 * 实盘录入与差异分流（4.11，spec count-management 录入遮蔽 / 待复盘阻断 / 容差分流）。
 * 录入接口响应不携带账面（遮蔽在契约层）；提交后按行分流：
 * >10% 阻断标待复盘（L1）；≤容差当场自动调整；>容差生成 COUNT 差异单挂 CountDiff 审批。
 */
public interface CountInputService {

    /**
     * 提交一行实盘（WAREHOUSE/ADMIN）：
     * - 差异率 >10% → 行标 RECOUNT、422 阻断（重录可清标记，spec 待复盘）
     * - 差异率 ≤ TOLERANCE_DEFAULT → 当场自动调整（引擎 + 凭证 + 行 ADJUSTED）
     * - 超容差 → 生成 DIFF_TYPE=COUNT 差异单 + CountDiff 审批（任务推进 ADJUSTING）
     * 响应 = 回显（账面/差异三列——提交后可见，spec 录入遮蔽）。
     */
    Map<String, Object> submitCount(String lineId, java.math.BigDecimal actualQty,
                                    String abnormalFlag, String remark);

    /**
     * 录入回显（提交后查询）：账面/差异三列 + 调整状态。
     */
    Map<String, Object> result(String lineId);
}
