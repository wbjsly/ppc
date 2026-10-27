package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/**
 * 项目结项检查（add-project-closure，S-4.8-07 / C-4.8-05）。
 * 职责：发起结项（生成检查单+六项校验数值证据）、重新校验、人工项确认、
 *       自定义非强制行、提交审批/撤回。审批回调与拦截见 PmClosureApprovalCallback / PmClosureGuard。
 */
public interface PmClosureService {

    /** 发起结项：ACTIVE→CLOSING，生成检查单 + 固定 6 行（自动项现算数值证据） */
    Map<String, Object> initiate(String projectId);

    /** 检查单详情（含行与遗留工单，供 11.12.1 渲染与 PMO 审批查看） */
    Map<String, Object> detail(String projectId);

    /** 重新校验：DRAFT 态重算自动项证据覆盖快照，人工项保留 */
    Map<String, Object> resubmit(String projectId);

    /** 人工项确认（ACCEPT/DOC/自定义行）：勾 PASS 必传 ≥1 附件否则 422 */
    Map<String, Object> confirmItem(String itemId, Map<String, Object> payload);

    /** 自定义非强制行新增（默认 MANDATORY_FLAG=0 走遗留通道） */
    Map<String, Object> addCustomItem(String projectId, Map<String, Object> payload);

    /** 删除自定义行（仅 DRAFT 态、自定义行） */
    void removeCustomItem(String itemId);

    /** 提交审批：强制项全 PASS + 人工项附件齐 + 已结算 + 总结报告齐 → FROZEN + ApprovalEngine */
    Map<String, Object> submitForApproval(String projectId);

    /** 撤回结项：CLOSING→ACTIVE，check 解锁回 DRAFT，审批单置 CANCELLED */
    Map<String, Object> withdraw(String projectId);

    /** 自动项证据计算（initiate/resubmit 复用；供测试直调） */
    Map<String, Object> computeEvidence(String projectId);

    /** 遗留工单草稿批量保存（PMO 审批前，DRAFT 态可改；审批通过时转 OPEN） */
    List<Map<String, Object>> saveRemediationDrafts(String projectId, List<Map<String, Object>> items);

    /** 遗留工单列表（11.12.1 页签；按项目） */
    List<Map<String, Object>> remediations(String projectId);
}
