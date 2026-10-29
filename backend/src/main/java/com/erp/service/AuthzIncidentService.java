package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 越权探测事件（add-authz-detection-console，spec authz-detection，BR-5.6-11/S-5.6-06）：
 * 阈值触发（告警与降权 1:1 合表）、敏感拦截判定、到期/手动恢复、处置三动作。
 */
public interface AuthzIncidentService {

    /**
     * 阈值触发（幂等）：该账号已有 ACTIVE 事件行 → 直接返回；否则单事务
     * 插事件行 + 双角色站内告警（ADMIN + UAR_AUDITOR）+ SECURITY_EVENT 审计。
     *
     * @return true=本次新触发；false=幂等跳过
     */
    boolean trigger(String userId, int hitCount, int threshold);

    /** 该账号是否处于降权存续期（AuthzDenyFilter 敏感拦截判定） */
    boolean hasActiveRestriction(String userId);

    /** 告警列表（分页倒序；status 过滤可空） */
    Map<String, Object> list(String status, Integer page, Integer size);

    /** 处置三动作（MISUSE/RESTORE/MALICIOUS；结论 ≥10 字；已处置 422） */
    void dispose(String id, String action, String conclusion, String trainingFlag);

    /** 手动提前恢复（幂等：非 ACTIVE 跳过） */
    void restoreNow(String id, String reason);

    /** 到期恢复扫描（ACTIVE 且 EXPIRE_AT<=now → RESTORED；幂等） */
    Map<String, Object> restoreExpired();
}
