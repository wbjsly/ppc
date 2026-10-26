package com.erp.service.fin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 账龄分段分析（8.2.2，spec ar-management：六段参数化，超90占比=BR-4.3-14 判定源） */
public interface ArAgingService {

    /**
     * 账龄分析（customerId 可空=全客户）。
     * 基数=已确认且未核销完的应收；按 DUE_DATE 与 baseDate 差值落段（参数 AR_AGING_BUCKETS）。
     * 返回 {buckets:[{label,base,ratio}], customers:[...], over90Ratio, mom:[{bucket,prev,curr,delta}]}
     */
    Map<String, Object> aging(String customerId, LocalDate baseDate);

    /** 某客户在指定基准日的分段基数（坏账计提试算复用，design D3） */
    List<Map<String, Object>> bucketBases(String customerId, LocalDate baseDate);
}
