package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 职级差旅标准 + 差旅报销单（add-cost-collection-freeze，FR-4.8-5-3）。 */
public interface PmTravelClaimService {

    // ---- 标准 ----

    /** 标准列表（职级×城市类别） */
    List<Map<String, Object>> listStandards();

    /** 保存标准（覆盖同职级同城市类别旧行） */
    Map<String, Object> saveStandard(Map<String, Object> payload);

    // ---- 报销单 ----

    /** 报销单列表 */
    List<Map<String, Object>> list(String projectId, String empId, String status);

    /**
     * 保存/提交报销单：项目可空（DEPT_ONLY 归部门费用）；超标（AMOUNT>DAYS×标准）必填原因
     * 且提交时自动发起 L2 单节点财务审批（ApprovalEngine）；标准内走常规审核队列。
     */
    Map<String, Object> save(Map<String, Object> payload, boolean submit);

    /** 常规审核（仅标准内单据；超标单据由 L2 审批通过后自动归集）：通过 → 差旅成本归集 + 凭证 */
    Map<String, Object> review(String id, Map<String, Object> payload);
}
