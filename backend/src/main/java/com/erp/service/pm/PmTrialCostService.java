package com.erp.service.pm;

import java.util.Map;

/**
 * 试制成本汇总分析（add-rnd-material，FR-4.8-10-5）。
 * 复用成本归集数据，按 WBS TASK_TYPE=TRIAL 过滤聚合（材料+工时+外协），
 * 比对试制任务预算，纳入项目整体偏差口径（沿 COST_TOLERANCE，不新建引擎）。
 */
public interface PmTrialCostService {

    /** 项目试制成本汇总（TRIAL 任务集聚合 + 预算比对 + 偏差率） */
    Map<String, Object> summary(String projectId);
}
