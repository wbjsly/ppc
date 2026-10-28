package com.erp.service.scm;

import java.util.Map;

/** CPFR 供给能力反馈（spec cpfr-collaboration R3）。 */
public interface CpfrFeedbackService {

    /**
     * 门户提交反馈（可供量/产能约束/风险提示）。
     * 协议非已启用 → 422；写 PORTAL 来源 + SUBMIT_AT（记分卡取数）。
     */
    Map<String, Object> submit(String supplierId, Map<String, Object> payload);

    /** 管理端周期反馈监控列表（先 evaluate 惰性推进，design D2） */
    java.util.List<Map<String, Object>> monitor(String agreementId, String periodTag);
}
