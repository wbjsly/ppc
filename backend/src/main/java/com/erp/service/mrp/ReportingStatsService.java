package com.erp.service.mrp;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 良率统计（change add-reporting-management，spec in-process-quality
 * 「标准良率按路线行维护与快照」，design D2 唯一口径）。
 * 工序级累计：实际良率 = 合格产出累计 / 累计投入（= Σ(GOOD+NG)）× 100%；
 * 偏差 = |实际良率 − 快照 STANDARD_YIELD| / STANDARD_YIELD × 100%；
 * 分母 0 返回 null（不判定）；REVERSED 单自动剔除（冲销回拨）。
 * 消费方：报工提交后校验、完工校验、冻结挂点三处共用同一方法。
 */
public interface ReportingStatsService {

    /**
     * 工序良率快照。
     *
     * @return {inputQty, goodQty, ngQty, actualYield(可空), standardYield(可空),
     *         deviation(可空), rptCount}
     */
    Map<String, Object> yieldOf(String moId, Integer opSeq);

    /**
     * 良率偏差是否超阈值（`YIELD_TOLERANCE`，默认 10%）。
     * 无标准良率（快照缺失）或分母 0 → 不超（不误冻结）。
     * 返回 Map：{exceeded, actualYield, standardYield, deviation}（可空字段）。
     */
    Map<String, Object> deviationExceeded(String moId, Integer opSeq);
}
