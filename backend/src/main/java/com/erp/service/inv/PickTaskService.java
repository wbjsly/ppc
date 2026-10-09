package com.erp.service.inv;

import com.erp.entity.inv.PickTask;

import java.util.Map;

/**
 * 拣货任务（4.7.1，spec picking-review 拣货任务生成与生命周期）：
 * 4.6.3 确认后自动生成（SRC UK 幂等）、主管改派/作废、状态机迁移（非法跃迁 422）、分页查询。
 */
public interface PickTaskService {

    /**
     * 从出库单据生成/同步拣货任务（design D1，4.6.3 confirm 同事务调用）：
     * 按 (srcType, srcDocNo) 幂等——已存在则同步行批次/仓位/数量（状态不动），不存在则建 CREATED 任务+行。
     * 行数据取单据行回写值，MUST NOT 重新计算推荐。
     */
    PickTask createFromDoc(String srcType, String srcDocNo);

    /** 任务详情（含行） */
    Map<String, Object> detail(String taskId);

    /** 分页队列：状态/来源类型/单号关键词筛选 */
    Map<String, Object> page(String status, String srcType, String keyword,
                             long current, long size);

    /** 改派拣货员（CREATED/PICKING，留痕前后人员与时间） */
    Map<String, Object> assign(String taskId, String picker);

    /** 作废（仅 CREATED，原因必填 422，CANCELLED） */
    Map<String, Object> cancel(String taskId, String reason);

    /**
     * 状态迁移（服务内共用）：from 必须匹配当前状态（@Version 乐观锁），
     * 非法迁移 422；影响行数 0 → 409 并发冲突。
     */
    PickTask transition(String taskId, String fromStatus, String toStatus);

    /** 过账联动：单据 POSTED → 任务 COMPLETED（无任务跳过，DONE/DIFF_PENDING 等已终态不动由调用方判定） */
    void markCompleted(String srcType, String srcDocNo);

    /**
     * 从波次生成 WAVE 合并拣货任务（spec wave-management FR-4.4-7-3，任务 5.1）：
     * 分配行按 (仓位, 批次) 聚合为任务行（qty 合计）、行携带归属明细 SRC_ALLOC
     * （[{shipId, shipNo, qty}] 供分播按订单拆回）、行序仓位号升序（偏差 D5 路径优化）；
     * 按 (WAVE, waveNo) 锁定读幂等（复用 createFromDoc 口径），恰好 1 张。
     */
    com.erp.entity.inv.PickTask createFromWave(String waveId);

    /**
     * 按来源撤销任务（波次作废联动，spec：作废撤销 WAVE 任务）：
     * 无任务空操作；CREATED → 走作废状态机（原因必填）；更强状态 → 终态保护跳过（不应发生）。
     */
    void cancelIfExists(String srcType, String srcDocNo, String reason);
}
