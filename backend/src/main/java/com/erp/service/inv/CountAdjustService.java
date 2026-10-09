package com.erp.service.inv;

import com.erp.entity.inv.InvCountLine;
import com.erp.entity.inv.InvCountTask;

import java.util.List;

/**
 * 盘点调整执行（4.11，spec count-management 调整双轨留痕 / 容差分流）。
 * 容差内自动调整（BR-4.4-37）与 CountDiff 审批通过（BR-4.4-38 后续）共用同一通道（design D5）：
 * 按行差异正负拆 ADJUST_OUT/ADJUST_IN 过引擎（引擎单请求单方向）→ 双凭证（1901/1403 对向）
 * → 行置 ADJUSTED 关联单据号 → 任务计数推进。
 */
public interface CountAdjustService {

    /**
     * 执行调整（调用方事务内）：盘亏行（DIFF_QTY<0）→ ADJUST_OUT；盘盈（>0）→ ADJUST_IN。
     * 每方向一个引擎请求 + 一张凭证；负库存由引擎 C-4.4-01 阻断并整体回滚。
     * 全部行 ADJUSTED 后由调用方触发 task.tryComplete。
     *
     * @param reason 调整原因（留痕：容差内自动 / 审批单号）
     * @return 调整单据号（OUT/IN 各一个，可能只有一种方向）
     */
    List<String> executeAdjustment(InvCountTask task, List<InvCountLine> lines, String reason);
}
