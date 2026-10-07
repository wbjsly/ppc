package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 2.9.1 成本分析查询（spec procurement-cost-analysis，design D2/D6）。
 * 全部读取走 READY 快照 + MetricGuard 已注册指标 + 行级法人过滤 + 查询审计。
 */
public interface CostAnalysisService {

    /** Tab1 成本构成：双口径五维聚合（分页） */
    Map<String, Object> composition(Map<String, Object> params);

    /** Tab1 价差明细下钻（逐笔 PO vs 发票，剔除暂估并给计数） */
    Map<String, Object> priceDiff(String monthTag, String itemCode, String supplierId);

    /** Tab2 价格趋势：月度均价 + 环比/同比/MA3 + 协议价参考线 */
    Map<String, Object> trend(String itemCode, String basis);

    /** Tab3 降本：环比/同比节约额，供应商/品类/采购员三维汇总 */
    Map<String, Object> savings(String monthTag, String dimension);

    /** 立即扫描：当日数据补算异动（不覆盖已处置） */
    Map<String, Object> scanNow();
}
