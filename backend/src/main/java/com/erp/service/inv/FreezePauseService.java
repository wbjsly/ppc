package com.erp.service.inv;

import com.erp.entity.inv.InvFreeze;

/**
 * 冻结执行的出库任务挂起（spec freeze-management ADDED 需求 ①，BR-4.4-32 补齐）：
 * 拣货任务 CREATED/PICKING、波次 CREATED/ALLOCATED/PICKING/SORTING → PAUSED，
 * 记来源冻结单号与暂停前状态；STAGING/SHIPPING 及拣货 PICKED+ 不挂
 * （过账引擎批次预算兜底）。解冻不自动恢复，主管经 resume 手动恢复。
 */
public interface FreezePauseService {

    /**
     * 同事务挂起含被冻结维度的在途拣货任务与波次（幂等：UPDATE WHERE status IN）。
     * 由 FreezeCallback 在冻结执行内调用。
     */
    void pauseOutbound(InvFreeze freeze);

    /**
     * 恢复被冻结挂起的任务：来源冻结单必须已 RELEASED（否则 422），
     * CAS 回 PAUSE_FROM_STATUS 并清标记（并发冲突 409）。
     *
     * @param entityType "PICK_TASK" 或 "WAVE"
     */
    void resume(String entityType, String id);
}
