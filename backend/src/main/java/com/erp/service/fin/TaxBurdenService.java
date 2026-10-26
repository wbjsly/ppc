package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 税负率月度监控（tax-risk-monitoring，支撑 FR-4.14-5-5 税负率合理性复核）。
 */
public interface TaxBurdenService {

    /**
     * 月度税负率指标（实时计算不落表，design D3）：
     * 增值税税负率 =（销项 − 进项 + 转出）÷ 不含税销售额（双阈值：下限 + 环比波动）；
     * 所得税贡献率 = 利润总额 ÷ 营业收入；所得税税负率 = 应纳所得税 ÷ 营业收入。
     * 分母为零 → 标注「不适用」不参与环比。超界 → L4 信号。
     *
     * @param periodEnd  计算至该所属期 yyyyMM（含）
     * @param months     回看月数（默认 6）
     */
    Map<String, Object> monthly(String periodEnd, int months);

    /**
     * L4 超界信号（供风险看板聚合）：最近 N 个月中增值税税负率低于下限或环比波动超限的月份。
     */
    List<Map<String, Object>> signals(String periodEnd, int months);
}
