package com.erp.service.inv;

import java.util.List;
import java.util.Map;

/**
 * 盘点任务（4.11，spec count-management 任务状态机 / 仓位盘点锁）。
 * CYCLE 周期盘点（4.11.1 手工选范围）/ FULL 全面盘点（4.11.2 一键全仓）。
 * 生成即 COUNTING：行 BOOK_QTY 定格 + 仓位锁生效（引擎校验链⑤消费）。
 */
public interface CountTaskService {

    /**
     * 周期盘点（4.11.1）：按仓库+可选仓位/物料筛选生成 CYCLE 任务。
     * 行 = 命中维度的（仓位×物料×批次）库存行，BOOK_QTY = 该维度在手合计（定格）。
     * 范围为空 422；WAREHOUSE/ADMIN 限权（服务层强制）。
     */
    Map<String, Object> createCycle(String warehouseCode, List<String> binCodes,
                                    String itemCode, String remark);

    /**
     * 全面盘点（4.11.2）：对选仓一键生成 FULL 任务（全部有货维度，'' 未分配位不入盘点范围）。
     */
    Map<String, Object> createFull(String warehouseCode, String remark);

    /** 任务分页（状态/类型/仓库筛选） */
    Map<String, Object> page(String status, String taskType, String warehouseCode,
                             long current, long size);

    /** 任务行列表（行状态/物料/关键字筛选） */
    List<Map<String, Object>> lines(String taskId, String countStatus, String keyword);

    /** 取消任务：仅 COUNTING 且零实盘录入可取消（解锁仓位，行级删除） */
    Map<String, Object> cancel(String taskId, String reason);

    /**
     * 任务详情（含行状态汇总）；行录入/差异回显由 CountInputService 承载。
     */
    Map<String, Object> detail(String taskId);

    /**
     * 状态推进：COUNTING → ADJUSTING（首张超容差差异单挂审批时调用，幂等）。
     */
    void markAdjusting(String taskId);

    /**
     * 尝试闭环（容差内自动调整完成 / 差异单全部 RESOLVED 后由调用方触发）：
     * 全部行 ADJUSTED → 置 DONE 并同事务物化 REPORT_JSON（design D8）；
     * 未齐则保持当前状态（幂等）。
     *
     * @return true = 本次完成 DONE
     */
    boolean tryComplete(String taskId);
}
