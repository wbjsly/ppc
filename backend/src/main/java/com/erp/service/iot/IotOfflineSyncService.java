package com.erp.service.iot;

import java.util.List;
import java.util.Map;

/**
 * 离线同步与冲突解决（13.5，BL-4.11-01 字典序，C-4.11-04 72h 锁定）。
 */
public interface IotOfflineSyncService {

    /**
     * 批量上传（≤100 条/批，逐条校验反馈）。
     *
     * @return {results:[{idempotentKey, result: SYNCED/FAILED/CONFLICT/DUPLICATE, detail}]}
     */
    Map<String, Object> uploadBatch(List<Map<String, Object>> records);

    /**
     * 同步过账：按 BIZ_TYPE 分发到对应业务（报工/扫码/巡检/异常）。
     * 幂等键已存在 → DUPLICATE 不重复过账。
     */
    Map<String, Object> syncOne(Map<String, Object> record);

    /** 冲突列表 */
    List<Map<String, Object>> conflictList(String status);

    /** 冲突裁决（人工确认差异/财务 L2 审批结果回写） */
    Map<String, Object> resolveConflict(String conflictId, String decision, String remark);

    /** 终端锁定状态（超 OFFLINE_SYNC_MAX_HOURS 锁定；预警比例黄闪） */
    Map<String, Object> lockStatus(String deviceCode);

    /** 队列列表（离线作业视图） */
    List<Map<String, Object>> queueList(String deviceCode, String status);

    /** 同步成功后清理本地缓存（服务端确认清单） */
    Map<String, Object> cleanupSynced(String deviceCode);

    /**
     * 72h 锁定扫描（IotLockScheduler 调用）：
     * 最早 PENDING 超 WARN_RATIO → 预警；超 MAX_HOURS → 锁定终端。
     */
    Map<String, Object> scanLocks();
}
