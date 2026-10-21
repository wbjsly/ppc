package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamSafetyLock;

import java.util.Map;

/**
 * 安全停机挂锁服务（add-asset-maintenance，design D4，BR-4.13-16 L1）。
 * 挂锁/解锁审计留痕；assertNoActiveLock 为公共服务方法，派工与未来调拨（7.4）/处置（7.6）共用。
 */
public interface EamSafetyLockService {

    /** 挂锁分页（状态/卡片可筛） */
    Page<EamSafetyLock> page(long current, long size, String cardNo, String status);

    /**
     * 挂锁（工具页手动或工单执行中/报修安全风险自动触发）。
     * 锁编号系统生成（LOCK+yyyyMMdd+4 位流水）；同卡已有 ACTIVE 锁 → 409。
     */
    Map<String, Object> lock(String cardId, String reason, String sourceWoNo);

    /**
     * 解锁（限 ROLE_EQUIP_MGR，记录解除人与时间）。
     */
    Map<String, Object> release(String lockId, String releaseReason);

    /**
     * 公共阻断方法：存在未解除挂锁 → ServiceException 422（含锁编号/挂锁人/挂锁时间）。
     * 派工、调拨（7.4）、处置（7.6）统一调用。
     */
    void assertNoActiveLock(String cardId);
}
