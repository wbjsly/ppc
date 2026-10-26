package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 成本偏差监控与冻结（add-cost-collection-freeze，FR-4.8-5-6 / C-4.8-02 / BR-4.8-21 双阈值）。 */
public interface PmCostVarianceService {

    /**
     * 单项目偏差现算：PV = 基线 × 时间进度%（线性 clamp [0,1]）；
     * 偏差率 =（实际累计 − PV）/ PV。
     * 返回 {projectId, baseline, pv, actual, deviationRate, frozen, byType{...}}
     */
    Map<String, Object> variance(String projectId);

    /**
     * 每日扫描（03:40 任务调用）：逐 ACTIVE 项目判定——
     * 实际累计 > 基线 → 冻结（写 FREEZE_FLAG + 通知 PM/PMO）；
     * 否则偏差率 > COST_TOLERANCE（SysParam）→ 写预警（YELLOW ≤10% / RED >10%）+ 通知 PM。
     * 已冻结项目跳过防重复通知。返回 {frozen, yellow, red, skipped}
     */
    Map<String, Object> dailyScan();

    /** 预警清单（按项目/日期筛选） */
    List<Map<String, Object>> listAlerts(String projectId, String handledFlag);

    /** 预警「已处理」标记 */
    Map<String, Object> markHandled(String alertId, String note);
}
