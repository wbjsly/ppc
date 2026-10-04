package com.erp.service.mdm;

import java.util.List;
import java.util.Map;

/**
 * 政策驱动税码批量变更工作台（1.6.2 增强，S-4.1-07 闭环，design D3/D4）。
 * 五类执行计划：①空链首段 ②尾+1衔接 ③未失效尾段内缩短+新建 ④中间段缩短+新建(自动失效日) ⑤阻断。
 * 历史（已失效区间）零改动；切换日 ≥ 今天（BR-4.1-18 工作台侧落地）。
 */
public interface MdmTaxWorkbenchService {

    /** 候选税码：非软删分组取链尾（计算态/链尾区间/建议切换日=尾+1），关键字筛选，空态 hint */
    Map<String, Object> candidates(String policyId, String keyword);

    /** 预检（dry-run 不落库）：逐行计划与校验，返回 rowNo/valid/reason/plan/appliedExpire */
    Map<String, Object> preview(String policyId, List<Map<String, Object>> rows,
                                String switchDate, String expireDate);

    /** 行级独立提交：复验计划、逐动作执行、SUCCESS/FAILED/PARTIAL 报告，上限 500 */
    Map<String, Object> submit(String policyId, List<Map<String, Object>> rows,
                               String switchDate, String expireDate);
}
