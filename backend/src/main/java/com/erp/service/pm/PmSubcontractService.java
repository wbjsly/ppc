package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 外协合同台账 + 付款单（add-cost-collection-freeze，FR-4.8-5-2）。 */
public interface PmSubcontractService {

    // ---- 合同台账 ----

    /** 合同列表（按项目筛选） */
    List<Map<String, Object>> listContracts(String projectId);

    /**
     * 新建/更新合同：WBS 可空（空则置 NO_WBS_FLAG=1，L4 提示标记）。
     * payload {id?, projectId, wbsId?, supplierId?, supplierName?, totalAmount}
     */
    Map<String, Object> saveContract(Map<String, Object> payload);

    // ---- 付款单 ----

    /** 付款单列表 */
    List<Map<String, Object>> listPayments(String projectId, String contractId, String status);

    /**
     * 保存/提交付款单：金额 ≤ 合同剩余余额（L1 硬阻断）；冻结项目 422。
     * payload {id?, contractId, amount, payDate, remark?}；submit=true 时提交。
     */
    Map<String, Object> savePayment(Map<String, Object> payload, boolean submit);

    /** 审核：通过 → 外协成本归集 + 凭证 + 回写合同已付累计 */
    Map<String, Object> reviewPayment(String id, Map<String, Object> payload);
}
