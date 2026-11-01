package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/** 行级权限管理台（add-perm-control-console，spec bi-row-permission）。 */
public interface RowPermissionService {

    /** 规则列表（可按授权对象/状态过滤） */
    List<Map<String, Object>> list(String userName, String roleCode, String status);

    /** 新建草稿（三维度 + 受控 SCOPE_EXPR 校验） */
    Map<String, Object> create(Map<String, Object> payload);

    /** 修改草稿（仅 DRAFT 可改） */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 提交审批（ROLE_DATA_GOV 单签） */
    Map<String, Object> submit(String id);

    /** 停用（ENABLED → DISABLED，生成停用快照） */
    Map<String, Object> disable(String id);

    /** 版本历史（快照列表） */
    List<Map<String, Object>> versions(String ruleId);

    /** 生效影响预览：目标用户并集后最终范围 + 每主体生效规则来源 */
    Map<String, Object> impactPreview(String userName);

    // ---- 回调入口（审批引擎）----

    /** 审批通过：PENDING_APPROVAL → ENABLED + 插版本快照 */
    void activateOnApproved(String ruleId);

    /** 审批驳回：→ DRAFT + 通知 */
    void rejectOnCallback(String ruleId);
}
