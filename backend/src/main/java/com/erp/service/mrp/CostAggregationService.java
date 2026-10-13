package com.erp.service.mrp;

import java.util.List;
import java.util.Map;

/**
 * 量产工单成本归集（change add-cost-aggregation，spec production-cost-aggregation）。
 * 重建式：每次归集按工单删旧插新；完工事件与 5.11 页面手动重跑共用同一入口。
 */
public interface CostAggregationService {

    /**
     * 归集（重建式，系统内部路径——完工事件触发，无角色门槛）：直接材料（投料×单价）
     * + 直接人工（报工工时×STD_LABOR_RATE）+ 制造费用（池×分摊比例）
     * → 汇总（总成本/单位成本）→ 标准成本法四分解差异。
     * 单价双缺/参数非法 422 阻断（FR-4.5-6-1 异常分支）；独立事务（失败不影响完工事务）。
     *
     * @return 归集汇总（summary 行 + 要素合计 + 缺素提示），供触发链/页面即时反馈
     */
    Map<String, Object> aggregate(String moId);

    /**
     * 手动重跑（5.11 页面入口）：写权限 COST_ACCOUNTANT/ADMIN，内部委托 aggregate。
     * 重建幂等；MUST NOT 触碰已生成凭证（spec「重跑不动凭证」，凭证侧幂等锚工单号）。
     */
    Map<String, Object> rerun(String moId);

    /** 归集详情：summary + 明细行（5.11.1/2 页面下钻） */
    Map<String, Object> detail(String moId);

    /** 归集列表（keyword=工单号/物料，period=yyyyMM 筛选） */
    List<Map<String, Object>> list(String keyword, String period);

    /**
     * 制造费用池录入/修改（期次唯一，upsert）：body = {period, poolAmt, rule, remark}。
     * 写权限 COST_ACCOUNTANT/ADMIN；rule ∈ TIME/MACHINE/QTY 非法 422。
     */
    Map<String, Object> savePool(Map<String, Object> body);

    /** 制造费用池列表（按期次倒序） */
    List<com.erp.entity.mrp.OverheadPool> listPools();

    /**
     * 差异报告主管审阅：RAISED → REVIEWED（spec「提交主管审阅」）。
     * 写权限 PLAN_MGR/ADMIN；无报告或已审阅 422。
     */
    void reviewVariance(String moId);

    /**
     * 关单校验（C-4.5-09，spec work-order-management MODIFIED）：
     * summary 存在 / 三要素无缺失标记 / 差异已处理（超阈值须报告 REVIEWED）。
     * 不通过抛 422 并列出引导项。
     */
    void checkForClose(String moId);
}
