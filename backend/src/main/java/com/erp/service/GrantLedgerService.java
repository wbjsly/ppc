package com.erp.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 授予台账（add-permission-approval-flow design D5/D6，C-5.6-01 锚点）：
 * 台账为生命周期事实源，角色绑定表仅作生效投影；回收幂等并留回收前快照。
 */
public interface GrantLedgerService {

    /**
     * 生效（同事务）：特权窗口校验 → 逐角色写台账 → 写角色投影 → 申请单回填
     * → 审计 → 站内通知（申请人 + 直属主管）。任一步失败整体回滚。
     *
     * @param requireWindow true = 特权权限，须在变更窗口内（否则 422 拒绝）
     * @return {grantIds:[...], expireAt, activatedRoles:[...]}
     */
    Map<String, Object> activate(String applyId, String targetUserId, List<String> roleCodes,
                                 String source, LocalDateTime expireAt, boolean requireWindow,
                                 String operator);

    /**
     * 回收（幂等）：非 ACTIVE 直接返回现态；ACTIVE → 删投影 + 台账置 RECLAIMED
     * + 回收前权限快照 + 申请单回写 EXPIRED + 审计。
     */
    Map<String, Object> reclaim(String ledgerId, String operator);

    /**
     * 复核权威回收（add-access-review-console design D5）：UAR 判定收回绕过
     * "岗位基线内角色保投影"守卫（回收即删），其余语义同 reclaim。
     */
    Map<String, Object> reclaimForReview(String ledgerId, String operator);

    /** 台账列表（status 空 = 全部），带用户名与角色名 */
    List<Map<String, Object>> list(String status);
}
