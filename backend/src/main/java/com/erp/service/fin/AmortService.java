package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 预提与待摊费用（M8-9，spec prepaid-amortization，FR-4.6-8-1~6）。
 * 计划驱动：登记生成计划行（L1 合计校验）→ 手动触发期末凭证（幂等 + DRAFT 三态）
 * → 实际冲销与差异调整（C-4.6-16）→ 年末余额检查（L4 页面提示）。
 */
public interface AmortService {

    /** 预提登记（FR-4.6-8-1）：金额>0、归属期格式；生成单期计提计划行（L1 合计=总额） */
    Map<String, Object> registerPrepaid(Map<String, Object> payload);

    /**
     * 待摊登记：月数≥1、金额>0；>12 月 L4 提示转长期待摊（BR-4.6-05，允许保存）；
     * 直线法生成计划行（月摊销=FLOOR(总额/月数,2)，尾差并入末期，FR-4.6-8-2）+ L1 合计校验。
     * 返回 L4 提示（l4Hint 非空时前端展示）。
     */
    Map<String, Object> registerDeferred(Map<String, Object> payload);

    /** 登记单列表（type=PREPAY|AMORT，含计划进度） */
    List<Map<String, Object>> listDocs(String type);

    /** 单据详情 + 计划行下钻 */
    Map<String, Object> docDetail(String id);

    /**
     * 生成本期计提/摊销凭证（FR-4.6-8-3 手动触发，决策⑧）：扫未生成计划行，
     * 一计划行一 DRAFT 凭证（FR-4.6-8-4 制单≠审核）+ 条件回写幂等；期间 CLOSED 422。
     * 返回 {generated, skipped}。
     */
    Map<String, Object> generate(String period);

    /**
     * 预提实际冲销（FR-4.6-8-5，决策⑨预提单主动作）：借 2251 / 贷 1002（PAYMENT，余额条件扣减）
     * 或贷 2202（ON_ACCOUNT 收票挂应付）；差异率 > 0.5% 差异行摘要含差异率标记（C-4.6-16）；
     * 差异行恒生成保借贷平衡，容差内普通摘要。已冲销 422。
     */
    Map<String, Object> settlePrepaid(String id, Map<String, Object> payload);

    /**
     * 年末余额检查（FR-4.6-8-6 L4 提示，决策⑨页面区块）：
     * 预提科目 2251 指定期间余额≠0 黄标；待摊 1801 余额 vs Σ未摊销计划剩余勾稽黄标。
     */
    Map<String, Object> yearEndCheck(String period);

    /** 结账第⑨项判定（FinPeriodServiceImpl 用）：期间未生成计划行数 */
    long notGeneratedCount(String period);
}
