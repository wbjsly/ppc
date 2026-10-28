package com.erp.service.intf;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.intf.IntfSubscription;

import java.util.Map;

/**
 * 事件订阅配置（add-event-subscription，FR-4.9-2-3）。
 * 伙伴 × 事件类型唯一（409）；URL 空=回落通道（零断流）；RETRY_MAX 1~5 行级死信阈值。
 * 由 ROLE_INTF_OPS / ADMIN 维护。
 */
public interface EventSubscriptionService {

    Page<IntfSubscription> page(long current, long size, String partnerCode, String eventType, String status);

    /** 创建订阅（UK 409 / RETRY_MAX 范围 422） */
    Map<String, Object> create(Map<String, Object> body);

    /** 修改订阅（快照语义：改动仅对新投递生效） */
    Map<String, Object> update(String id, Map<String, Object> body);

    /** 启停（停用 → 该类型新投递回落通道 URL） */
    Map<String, Object> toggle(String id, String status);
}
