package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/**
 * 脱敏规则生命周期（add-data-extract-console，spec bi-mask-rule-management）：
 * 类型化样式 CRUD + 数据治理专员单签审批激活 + 变更留痕。
 * 掩码执行在 QueryGovernance（bi-query-governance 承接）。
 */
public interface MaskRuleService {

    /** 规则列表（TYPE_COL_MASK / COL_SENSITIVE），附最近留痕 */
    List<Map<String, Object>> list(String ruleType);

    /** 新建规则（默认 DRAFT，须 submit 审批后 ENABLED 生效） */
    Map<String, Object> create(Map<String, Object> payload);

    /** 修改样式/适用角色/动作：仅 DRAFT 可改；留痕 UPDATE_STYLE / UPDATE_ROLE */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 停用（ENABLED → DISABLED，留痕）；停用即时生效无需审批 */
    Map<String, Object> disable(String id);

    /** 提交审批（DRAFT/REJECTED → PENDING_APPROVAL，ROLE_DATA_GOV 单签） */
    Map<String, Object> submit(String id);

    /** 变更历史（审计只增） */
    List<Map<String, Object>> logs(String ruleId);

    // ---- 回调入口（MaskRuleApprovalCallback 调用）----

    /** 审批通过：PENDING_APPROVAL → ENABLED + 留痕 */
    void activateOnApproved(String ruleId);

    /** 审批驳回：→ DRAFT + 留痕 + 通知提交人 */
    void rejectOnCallback(String ruleId);
}
