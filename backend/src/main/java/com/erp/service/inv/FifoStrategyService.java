package com.erp.service.inv;

import java.util.Map;

/**
 * 先进先出策略页（4.6.1，spec outbound-strategy）：
 * B 推荐试算（委托 BatchRecommendService，只读）+ C 偏离监控台账查询与落账。
 */
public interface FifoStrategyService {

    /** B 试算：FIFO+FEFO《批次推荐表》（只读，不动库存/预留/单据） */
    Map<String, Object> simulate(String warehouseCode, String itemCode,
                                 java.math.BigDecimal qty, boolean binLevel);

    /** C 偏离台账分页：单据号/物料/操作人/时间范围筛选 */
    Map<String, Object> deviations(String docNo, String itemCode, String createBy,
                                   String dateFrom, String dateTo,
                                   long current, long size);

    /**
     * 记录改批偏离（领料行级改批与 4.6.3 确认改写共用）：
     * 实际批次 == 推荐批次 → 不落账（无偏离）；原因为空 → 422。
     */
    void recordDeviation(String srcDocType, String srcDocNo, Integer lineNo,
                         String itemCode, String warehouseCode,
                         String recommendedBatch, String actualBatch, String reason);
}
