package com.erp.service.bi;

import java.util.Map;

/** 2.9.3 价格监测工作台（spec price-monitoring）。 */
public interface PriceMonitorService {

    /** 异动清单（筛选 + 下钻数据） */
    Map<String, Object> alertList(String monthTag, String status, String itemCode);

    /** 处置状态机：OPEN → HANDLED / IGNORED，留痕不可篡改 */
    Map<String, Object> handle(String alertId, String action, String note);

    /** 阈值参数读取（在线生效，回退默认值） */
    Map<String, Object> threshold(String key, String fallback);

    /** 阈值在线调整（留痕 old/new/by/at；对新判定生效不追溯） */
    Map<String, Object> setThreshold(String key, String value, String remark);

    /** 协议价偏离清单（最近采购均价 vs 框架协议价，超容差高亮 + 价控日志关联） */
    Map<String, Object> deviationList();

    /** 比价异常清单（BR-4.2-12 偏离均值 >20% 集中呈现，只读） */
    Map<String, Object> quoteAnomalies();

    /** 阈值数值（在线优先，config 表缺失回退 fallback） */
    java.math.BigDecimal thresholdValue(String key, java.math.BigDecimal fallback);
}
