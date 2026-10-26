package com.erp.service.hr;

import java.util.List;
import java.util.Map;

/** 借调成本月度分摊凭证（secondment-cost-allocation，BR-4.7-20）。 */
public interface HrSecondmentVoucherService {

    /**
     * 月度生成（每月 1 日跑上月）：在途借调单按分摊比例 × 月人力成本生成成对往来凭证；
     * 幂等锚点 sourceDocNo=单号@yyyyMM；EXPIRED_ALERT 跳过并告警；失败逐单容错。
     * 返回 {generated, skippedExpired, failed, details}。
     */
    Map<String, Object> monthlyGenerate(String ym);

    /** 分摊预览：ym 月应付分摊清单（单号/员工/在途天数/成本基数/分摊额/估算标记） */
    List<Map<String, Object>> preview(String ym);
}
