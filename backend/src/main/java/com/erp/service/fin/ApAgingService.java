package com.erp.service.fin;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 应付账龄分析（8.3.2，spec ap-management：复用 AR 六段参数，基数=POSTED 且未清>0 按 DUE_DATE 落段） */
public interface ApAgingService {

    /**
     * 账龄分析（supplierId 可空=全供应商）。
     * 基数 = STATUS=POSTED 且未清（TOTAL_AMOUNT − PAID_AMOUNT）> 0 的应付；
     * 按 DUE_DATE 与 baseDate 差值落段（参数 AR_AGING_BUCKETS，与应收账龄对称）。
     * 返回 {buckets:[{label,base,ratio}], suppliers:[...], overdueTotal, overdueRatio,
     *       nullDueCount（NULL 到期日跳过张数）}
     */
    Map<String, Object> aging(String supplierId, LocalDate baseDate);

    /**
     * 存量发票到期日批量回填（spec ap-management 账龄基准治理）：
     * DUE_DATE IS NULL 且供应商账期为数字天数的发票，按 INVOICE_DATE+账期回填。
     * 返回 {updated, skippedNoTerms}。
     */
    Map<String, Object> backfillDueDate();
}
