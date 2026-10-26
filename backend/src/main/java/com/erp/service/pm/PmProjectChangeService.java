package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 项目变更申请与影响分析（add-project-change，FR-4.8-8-1/2）。 */
public interface PmProjectChangeService {

    /**
     * 保存/提交变更申请（五类）：WBS 关联 L1、倒签 L1、结项中项目 L1、描述<20 字 L4。
     * payload {projectId, changeType, reason, description?, effectiveDate, wbsIds[],
     *          scopeImpact?, scheduleImpactDays?, resourceImpact?, budgetAdd?,
     *          isContractFlag?, contractId?, contractNo?, customerConfirmFlag?}
     * 提交时服务端算 BUDGET_RATE（追加/基线），涉合同取或（手动 OR >10% 强制），三级路由提交审批。
     */
    Map<String, Object> save(Map<String, Object> payload, boolean submit);

    /** 变更单清单（按项目/状态/类型筛选） */
    List<Map<String, Object>> list(String projectId, String status, String changeType);

    /** 单据详情（含受影响 WBS 集） */
    Map<String, Object> detail(String id);

    /**
     * 执行推进：EXECUTING → DONE（仅变更创建人/PM）→ VERIFIED 由 verify() 承担。
     * payload {opinion?}
     */
    Map<String, Object> advance(String id, Map<String, Object> payload);

    /**
     * PMO 核验：DONE → VERIFIED（仅 PMO/ADMIN）。
     * payload {opinion?, verifyDeviation?}；偏差>50% → L4 提示 + 升级通知 PMO。
     */
    Map<String, Object> verify(String id, Map<String, Object> payload);

    /** 台账统计（按类型/原因/影响维度分组计数） */
    Map<String, Object> stats(String projectId);
}
