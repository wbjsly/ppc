package com.erp.service.bi;

import java.util.List;
import java.util.Map;

/** 敏感字段临时授权（add-perm-control-console，spec bi-temp-grant，≤24h 自动收回）。 */
public interface TempGrantService {

    /** 申请查看明文（服务端钳制有效期 ≤24h，超限 422） */
    Map<String, Object> apply(Map<String, Object> payload);

    /** 我的/指定用户申请记录 */
    List<Map<String, Object>> listMine(String userName);

    /** 全部记录（管理视角，可按状态过滤） */
    List<Map<String, Object>> listAll(String status);

    /** 主动收回 */
    Map<String, Object> revoke(String id);

    /**
     * 是否存在覆盖该字段的有效（ACTIVE 未过期）临时授权。
     * maskIfHit 内部调用；无授权返回 false。
     */
    boolean hasActive(String userName, String fieldKey);

    // ---- 定时收回入口 ----

    /** 扫描 ACTIVE 且已过期 → EXPIRED + 留痕（@Scheduled 调用） */
    int expireOverdue();
}
