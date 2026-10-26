package com.erp.service.hr;

import java.util.Map;

/** 工资凭证联动（payroll-disbursement：审批过计提/发放付款/作废冲销，SOURCE_TYPE=PAYROLL 幂等）。 */
public interface HrPayVoucherService {

    /**
     * 计提凭证（PayrollRun 审批过）：
     * 借 费用-工资（按 COST_CENTER 分摊，映射缺失用 DEFAULT_EXPENSE 并 L4 留痕）
     * + 借 费用-社保（单位部分重算）
     * 贷 应付工资 + 应付社保公积金（单位）
     * 更正批次（CORRECTION）按差额方向生成。
     */
    Map<String, Object> accrual(String runId);

    /** 付款凭证（发放登记 PAID）：借 应付工资；贷 银行（实发）+ 应交个税 + 应付社保（个人代扣）+ 其他代扣款。 */
    Map<String, Object> payment(String runId);

    /** 冲销该批次全部 PAYROLL 凭证（批次作废时调用）。 */
    Map<String, Object> reverseByRun(String runId, String reason);
}
