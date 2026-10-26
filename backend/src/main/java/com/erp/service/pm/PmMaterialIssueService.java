package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 项目领料单（add-cost-collection-freeze，FR-4.8-5-1；材料成本归集 + 库存扣减）。 */
public interface PmMaterialIssueService {

    /** 单据列表（按项目/状态筛选） */
    List<Map<String, Object>> list(String projectId, String status);

    /** 单据详情（含明细行 + 凭证） */
    Map<String, Object> detail(String id);

    /**
     * 保存/提交领料单：WBS 必填 L1 硬阻断（BR-4.8-20）；冻结项目 422。
     * payload {id?, projectId, issueDate, remark?, lines:[{wbsId, itemId, itemName?, batchNo?, warehouseCode, qty, unitPrice}]}
     * submit=true 时提交（扣库存），否则存草稿。
     */
    Map<String, Object> save(Map<String, Object> payload, boolean submit);

    /** 审核：通过 → 材料成本归集 + 凭证（驳回不归集、不扣库存——提交时已扣，驳回走手工冲减留桩） */
    Map<String, Object> review(String id, Map<String, Object> payload);
}
