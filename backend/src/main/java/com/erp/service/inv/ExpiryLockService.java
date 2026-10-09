package com.erp.service.inv;

import java.util.List;
import java.util.Map;

/**
 * 效期锁定管理（4.10.2，spec expiry-management 需求②③）：
 * 人工锁定（收紧侧即时生效免审批，C-4.4-03 授权口径——解除唯一入口=评估放行）、
 * 锁定台账与变更历史、手动触发扫描。
 */
public interface ExpiryLockService {

    /**
     * 人工锁定：对未到线批次提前置锁（LOCK_SOURCE=MANUAL），原因必填（空 422）、
     * 即刻生效、WAREHOUSE/ADMIN（否则 403）、同事务写变更历史。
     * 已锁定批次重复锁定 422。
     */
    Map<String, Object> manualLock(String batchNo, String itemCode, String reason);

    /** 锁定批次台账：EXPIRY_LOCK_FLAG=1 全量，筛选物料/仓库/锁定来源，分页 */
    Map<String, Object> ledger(String itemCode, String warehouseCode, String lockSource,
                               long current, long size);

    /** 变更历史（flag 变化才落的流水），按批次/物料筛选，时间倒序 */
    List<Map<String, Object>> history(String batchNo, String itemCode, long size);

    /** 手动触发扫描（WAREHOUSE/ADMIN）：跑 scanExpiryLock 返回变更批次数 */
    Map<String, Object> triggerScan();
}
