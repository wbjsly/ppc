package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 间接成本分摊引擎（add-cost-collection-freeze，FR-4.8-5-5）。 */
public interface PmAllocService {

    /** 规则类型 */
    String RULE_HOURS = "HOURS";
    String RULE_BUDGET = "BUDGET";

    /** 规则列表（可含停用） */
    List<Map<String, Object>> listRules();

    /** 保存规则（每项目至多一条生效；同项目重复保存=覆盖类型） */
    Map<String, Object> saveRule(Map<String, Object> payload);

    /** 停用规则 */
    void disableRule(String id);

    /** 费用池录入列表（按期间） */
    List<Map<String, Object>> listPools(String period);

    /** 费用池录入 */
    Map<String, Object> savePool(Map<String, Object> payload);

    /** 分摊结果（按期间查分摊明细） */
    List<Map<String, Object>> listDetails(String period);

    /**
     * 执行月度分摊（定时任务/手工触发）：按规则（工时占比|预算占比）把当期费用池
     * 分摊至已配置规则项目，生成 INDIRECT 归集 + 凭证；缺规则项目跳过 + L4 提示。
     * 幂等：同 PERIOD + POOL_ID 已有明细则跳过。
     */
    Map<String, Object> runPeriod(String period);
}
