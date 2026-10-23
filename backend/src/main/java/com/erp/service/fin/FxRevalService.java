package com.erp.service.fin;

import java.util.Map;

/**
 * 外币期末重估（add-fx-revaluation，spec fx-revaluation，FR-4.6-4-1~6）。
 * 重估范围：外币货币性项目——AP 未清发票 / 外币银行账户余额 / AR 未清发票；
 * 非货币性项目（存货/固资）不纳入。汇兑差额 = 外币额 ×（期末汇率 − 原记账汇率）。
 */
public interface FxRevalService {

    /**
     * 试算（不落库，design D6）：扫描三类外币货币性项目 → 按期末日中间价计算逐笔汇兑差额 →
     * 返回明细 + 币种×类型汇总。期末汇率缺失时逐币种列示阻断（C-4.6-03 L1）。
     * period：YYYYMM。
     */
    Map<String, Object> trialRun(String period);

    /**
     * 生成重估批次（design D4/D7）：重跑同一计算内核 → 上期自动冲回（POSTED 红冲/DRAFT 作废）→
     * 落台账逐笔 + DRAFT 重估凭证（批次锚 REVAL-YYYYMM）。同期重复生成 422。
     */
    Map<String, Object> generate(String period);

    /**
     * 批次列表（期间倒序）：每期 重估额 / 冲回额（本期对上期的红冲影响）/ 净额 / 凭证状态。
     * 净额 = 本期重估额 + 冲回影响（红冲 6604 行借贷符号化）。
     */
    java.util.List<Map<String, Object>> batches();

    /**
     * 批次明细下钻：币种×类型汇总 + 逐笔台账 + 凭证链（本批凭证与冲回凭证）。
     */
    Map<String, Object> batchDetails(String period);
}
