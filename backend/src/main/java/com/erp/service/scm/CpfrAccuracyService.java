package com.erp.service.scm;

import java.util.Map;

/** CPFR 预测准确率月度评估（spec cpfr-collaboration R7）。 */
public interface CpfrAccuracyService {

    /**
     * 评估 yyyyMM 月度准确率：1 − Σ|实际−预测|/Σ预测（按已转化行聚合）。
     * 连续 3 周期 < 70% 或例外占比 > 30% → 协议「待复盘」+ 暂停生成订单。
     *
     * @return 评估摘要 {plans, agreements, reviewTriggered}
     */
    Map<String, Object> evaluateMonth(String yyyyMM);
}
