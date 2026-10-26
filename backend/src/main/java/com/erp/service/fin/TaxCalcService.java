package com.erp.service.fin;

import com.erp.entity.fin.TaxCalcLedger;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 计税引擎（M9-1，spec tax-calculation，4.14 流程 1）。
 * match/calc 保持纯函数形态（design D1/D2）——后续 SO/PO/报销嵌入变更可直接注入复用。
 */
public interface TaxCalcService {

    /**
     * 税码匹配（FR-4.14-1-2 / C-4.14-01 / BR-4.14-10）：
     * 按 (SCOPE, CALC_TYPE) 过滤生效码逐个按业务发生日 trial 收集命中集——
     * 0 → 422（税率主数据缺失或已过期）；1 → 唯一命中；≥2 → 候选列表（L4 人工选）。
     */
    Map<String, Object> match(String trxType, String scope, String calcType,
                              String rateKind, LocalDate businessDate);

    /**
     * 税额计算（FR-4.14-1-3/4 / BR-4.14-07/08/12）：
     * 含税先价税分离；折扣/运费按各行不含税占比分摊后逐行计税；
     * SIMPLE 按征收率且 deductible=false；尾差超容差 overTolerance=true（L4 不阻断）。
     */
    Map<String, Object> calc(Map<String, Object> payload);

    /** 台账落库（RECORDED 留痕；payload 含 match+calc 全要素） */
    Map<String, Object> ledger(Map<String, Object> payload);

    /** 台账列表（筛选：状态/交易类型/税码/期间） */
    List<TaxCalcLedger> list(String status, String trxType, String taxCode, String period);

    /** 补录对手方/期间/摘要 → APPLYING（三字段必填，FR-4.14-1-4） */
    TaxCalcLedger submitApply(String id, String counterparty, String period, String summary);

    /**
     * 生成税务凭证（FR-4.14-1-5，DRAFT 三态）：SALES→贷 222102 / PURCHASE 可抵扣→借 222101 /
     * SIMPLE→借 660299；锚 SOURCE_TYPE='TAXCALC'+台账行号；重复生成 422。
     */
    Map<String, Object> generateVoucher(String id);

    /**
     * 合规校验报告（9.1.3，核心能力 4）：①税码过期/临期引用 ②分类编码未登记（黄标铺垫）
     * ③台账 vs 总账 222101/222102 勾稽（C-4.14-04 计税侧前置）④SIMPLE 抵扣违规。
     */
    Map<String, Object> complianceReport(String period);
}
