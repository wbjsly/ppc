package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 审计取证导出（add-audit-log-console，S-5.6-07 / BR-5.6-14）：
 * 调阅申请 → 内审+DPO 双签 → 生成包（封条预计算）→ 分片下载（重算比对）→ 过期销毁。
 */
public interface ForensicService {

    /** 调阅申请（目的 ≥10 字 / 时间范围 / 操作类型 / 对象范围） */
    Map<String, Object> apply(String purpose, String rangeFrom, String rangeTo,
                              String actionFilter, String objectFilter);

    List<Map<String, Object>> list();

    /** 内审签署（ROLE_UAR_AUDITOR；自签 403；重复签 422） */
    void signAudit(String id, String opinion);

    /** DPO 签署（ROLE_DPO；内审未签 422；顺序闭环） */
    void signDpo(String id, String opinion);

    /** 生成取证包（双签齐备校验 + 确定性排序预计算封条 + 过期 now+7d） */
    Map<String, Object> generatePackage(String requestId);

    /** 分片下载（三重校验 + 重算 SHA-256 比对封条 + CSV 字节流含脱敏 + 下载自身留痕） */
    Map<String, Object> download(String packageId, int shard);

    /** 使用完毕确认（提前销毁，幂等） */
    void confirmUsed(String packageId);

    /** 过期销毁扫描（GENERATED 且 EXPIRE_AT<=now → DESTROYED + 审计留痕；幂等） */
    Map<String, Object> sweepExpired();
}
