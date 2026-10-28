package com.erp.service.scm;

import java.util.Map;

/** CPFR 分歧例外与协商（spec cpfr-collaboration R4，BR-4.9-32 两态闭环）。 */
public interface CpfrExceptionService {

    /**
     * 反馈提交后立即比对：|分歧率| > 协议阈值 → 自动生成例外项（幂等：同周期同 SKU 唯一）。
     */
    void detect(CpfrFeedbackRef ref);

    /** 例外清单（管理端；含调整历史） */
    java.util.List<Map<String, Object>> list(String agreementId, String status);

    /** 供应商门户：提交调整意见（数量/说明），写调整历史 SUPPLIER_OPINION */
    Map<String, Object> supplierOpinion(String supplierId, Map<String, Object> payload);

    /** 计划员终确认：accept=达成 / reject=驳回（写 PLAN_CONFIRM 历史；驳回可重开协商） */
    Map<String, Object> finalConfirm(String exceptionId, boolean accept, String remark);

    /** 门户待处理例外（仅本供应商协议下的待协商项） */
    java.util.List<Map<String, Object>> portalList(String supplierId);

    /** 反馈落库后的轻量引用（避免循环依赖 feedback 实体） */
    record CpfrFeedbackRef(String agreementId, String periodTag, Integer weekOffset,
                           String itemCode,
                           java.math.BigDecimal demandQty, java.math.BigDecimal supplyQty) {
    }
}
